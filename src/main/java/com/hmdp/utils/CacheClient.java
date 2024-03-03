package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        // 需要将参数中的value序列化为字符串对象
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                          Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis中查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在缓存
        if (StrUtil.isNotBlank(json)) {  // 这里为空有两种情况：null或者""
            // 3.存在，直接返回，将json数据转换为shop对象
            return JSONUtil.toBean(json, type);
        }
        // 判断是否是""的情况， 下面只有两种该情况null或者""，null需要查询数据库，但""不查
        // 如果出现空值，则直接返回店铺不存在
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4.不存在根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 如果查询的结果为""，则将空值存到缓存中
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        // 7.返回店铺信息
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 设置逻辑过期时间解决缓存击穿问题
     *
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicExpire(String keyPrefix, ID id,Class<R> type, Function<ID,R> dbFallback,
                                         Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis中查询商品缓存
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在缓存
        if (StrUtil.isBlank(json)) {  // 这里为空有两种情况：null或者""
            // 3.存在，直接返回，将json数据转换为shop对象
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期直接返回商铺信息
            return r;
        }
        // 5.2 过期了，尝试获取互斥锁来重建缓存数据
        // 6.缓存重建
        // 6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2 判断是否获取锁成功
        if (isLock) {
            // TODO 6.3 成功,开启独立线程,实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 6.4 失败,直接返回过期商铺信息


        // 7.返回店铺信息
        return r;
    }
    /**
     * 尝试获取锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // return flag;  不能直接返回flag，否则可能会出现空指针异常的情况
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
