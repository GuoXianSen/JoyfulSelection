# JoyfulSelection
乐享优选点评项目

本项目是一个点评类项目，实现了短信登录、探店点评、商品秒杀、每日签到、好友关注等多个模 块。用户可以浏览首页推荐内容，搜索附近的商家，查看商家的详情和评价以及发表探店博客，抢购商家发布 的限时秒杀商品。

## 主要功能

+ 短信登录
  + Redis的共享session应用
+ 商户查询缓存
  + 企业缓存使用技巧
  + 缓存雪崩、穿透等问题解决
+ 达人探店
  + 基于List的点赞列表
  + 基于SortedSet的点赞排行榜
+ 优惠券秒杀
  + Redis的计数器
  + Lua脚本
  + Redis分布式锁
  + Redis的三种消息队列
+ 好友关注
  + 基于Set集合的关注、取关、共同关注
  + 消息推送
+ 附近的商户
  + GeoHash的应用（根据地理坐标获取数据）
+ 用户签到
  + BitMap数据统计功能

+ UV统计
  + HyperLogLog的统计功能
