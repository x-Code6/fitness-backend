-- 秒杀资格原子校验脚本（订单服务执行）
-- KEYS[1] = seckill:stock:{activityId}   库存（String）
-- KEYS[2] = seckill:bought:{activityId}  已购用户集合（Set，一人一单）
-- ARGV[1] = userId
-- ARGV[2] = 资格集合 TTL（秒，= 距活动结束的秒数）
--
-- 返回：0 抢购成功；1 库存不足；2 重复购买
--
-- 为什么用 Lua：GET 判断库存、SISMEMBER 判断资格、DECR 扣减、SADD 记账
-- 共 4 步，任何两步之间都可能被并发请求插入；Redis 单线程执行整段 Lua，
-- 天然保证这段逻辑的原子性——这就是"秒杀防超卖"的核心。
if tonumber(redis.call('GET', KEYS[1]) or '0') <= 0 then
    return 1
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
-- 资格集合跟随活动结束时间过期，活动结束后自动清理
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
return 0
