-- 秒杀名额回补（Redis 侧）
-- KEYS[1] = seckill:stock:{activityId}   库存
-- KEYS[2] = seckill:bought:{activityId}  已购用户集合
-- ARGV[1] = userId
--
-- 回补必须和"从资格集合移除这个人"一起做：
-- 只 INCR 不减集合，用户还是不能再抢；只 SREM 不 INCR，库存少一个。
-- 两条命令放进一段 Lua，Redis 单线程执行，不会出现中间态。
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
return 1
