-- 优惠券领取资格原子校验脚本（课程服务执行）——与秒杀 Lua 同构
-- KEYS[1] = coupon:stock:{couponId}  可领库存（String）
-- KEYS[2] = coupon:got:{couponId}    已领用户集合（Set，一人一张）
-- ARGV[1] = userId
-- ARGV[2] = 两个 Key 的 TTL（秒 = 距领取截止时间）
-- 返回：0 领取成功；1 已抢完；2 重复领取
if tonumber(redis.call('GET', KEYS[1]) or '0') <= 0 then
    return 1
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
return 0
