-- ============================================================
-- 秒杀券领取（Lua 保证"判限领 + 扣库存 + 计数"三步原子）
--
-- ★为什么要 Lua 而不是先查再改：领券是高并发写，
--   "查已领张数 → 判断 → 扣库存 → 写记录"之间有时间窗，
--   并发下会出现两个人同时看到"还剩 1 张"，最后库存扣成 -1。
--   Redis 单命令是原子的，但多命令组合必须靠 Lua 变成一个原子操作。
--
-- ★v2 的改动：原来用 SET(SISMEMBER/SADD) 表达"这人领过没有"，只能一人一张；
--   现在要支持"每人限领 N 张"，所以换成 Hash 记张数（HGET/HINCRBY），
--   仍然是同一段 Lua、同一个机制，只是把"有没有"换成"几次"。
--
-- KEYS[1] = coupon:stock:{couponId}  剩余可领（String）
-- KEYS[2] = coupon:got:{couponId}    已领张数（Hash，field=userId）
-- ARGV[1] = userId
-- ARGV[2] = TTL 秒（到领取截止）
-- ARGV[3] = limitPerUser，★0 = 不限领
--
-- 返回：0 成功 / 1 已抢完 / 2 超过每人限领
-- ============================================================
local limit = tonumber(ARGV[3])
if limit > 0 then
    local got = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
    if got >= limit then
        return 2
    end
end

-- 库存 key 不存在（没预热过）也按"已抢完"处理：宁可不让人领，
-- 也不能在没有账本的情况下凭 Redis 缺键就把库存当成无限
if tonumber(redis.call('GET', KEYS[1]) or '0') <= 0 then
    return 1
end

redis.call('DECR', KEYS[1])
redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
return 0
