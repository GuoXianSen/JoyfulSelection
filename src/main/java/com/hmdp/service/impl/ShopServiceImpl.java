package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透  第四个参数可以进行简写：this::getById
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // // 用互斥锁解决缓存击穿问题
        // Shop shop = queryWithMutex(id);
        // 用逻辑过期时间解决缓存击穿问题
        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回店铺信息
        return Result.ok(shop);
    }

    /**
     * 使用互斥锁解决缓存击穿问题
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis中查询商品缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在缓存
        if (StrUtil.isNotBlank(shopJson)) {  // 这里为空有两种情况：null或者""
            // 3.存在，直接返回，将json数据转换为shop对象
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断是否是""的情况， 下面只有两种该情况null或者""，null需要查询数据库，但""不查
        // 如果出现空值，则直接返回店铺不存在
        if (shopJson != null) {
            // 返回错误信息
            return null;

        }
        // 4.实现缓存重建
        // 4.1 获取互斥锁
        String lockkey = "lock:shopL" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockkey);
            // 4.2 判断是否获取成功
            if (!isLock) {
                // 4.3 失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 成功，根据id查询数据库
            shop = getById(id);
            // 模拟重建延迟
            Thread.sleep(200);
            // 5.不存在，返回错误
            if (shop == null) {
                // 如果查询的结果为""，则将空值存到缓存中
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockkey);
        }
        // 8.返回店铺信息
        return shop;

    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 设置逻辑过期时间解决缓存击穿问题
     *
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis中查询商品缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在缓存
        if (StrUtil.isBlank(shopJson)) {  // 这里为空有两种情况：null或者""
            // 3.存在，直接返回，将json数据转换为shop对象
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期直接返回商铺信息
            return shop;
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
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 6.4 失败,直接返回过期商铺信息


        // 7.返回店铺信息
        return shop;
    }

    /**
     * 解决缓存穿透问题
     *
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis中查询商品缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在缓存
        if (StrUtil.isNotBlank(shopJson)) {  // 这里为空有两种情况：null或者""
            // 3.存在，直接返回，将json数据转换为shop对象
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断是否是""的情况， 下面只有两种该情况null或者""，null需要查询数据库，但""不查
        // 如果出现空值，则直接返回店铺不存在
        if (shopJson != null) {
            // 返回错误信息
            return null;
        }

        // 4.不存在根据id查询数据库
        Shop shop = getById(id);
        // 5.不存在，返回错误
        if (shop == null) {
            // 如果查询的结果为""，则将空值存到缓存中
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回店铺信息
        return shop;
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

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
