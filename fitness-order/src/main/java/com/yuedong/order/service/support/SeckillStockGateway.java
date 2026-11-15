package com.yuedong.order.service.support;

import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 秒杀库存与购买资格的原子扣减（订单域）。
 *
 * <p>★为什么必须是一段 Lua 而不是几条命令：判库存、判一人一单、扣减、记账共 4 步，
 * 任何两步之间都可能被并发请求插进来（两个人同时看到"还剩 1 件"）。
 * Redis 单线程执行整段脚本，天然把这段变成一次不可分割的操作——这是"秒杀不超卖"的全部依据。</p>
 *
 * <p>★这里<b>只管 Redis，不管 MySQL 的 {@code t_seckill_activity.remain}</b>：
 * Redis 是挡峰值的那一层，DB 是最终一致的账。两者的差额靠"扣减失败即回补 Redis"
 * 和对账任务收敛，不靠把两次写包成一个事务（跨服务也没有分布式事务可用）。</p>
 */
@Service
public class SeckillStockGateway {

    /** 资格集合的 TTL 下限：活动只剩几十秒时也要留够时间把订单落库，否则资格先过期就拦不住重复买了 */
    private static final long MIN_QUALIFY_TTL_SECONDS = 60;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> seckillScript;
    /** 回补：INCR 库存 + SREM 资格集合，必须原子 */
    private final DefaultRedisScript<Long> restoreScript;

    public SeckillStockGateway(StringRedisTemplate redis) {
        this.redis = redis;

        DefaultRedisScript<Long> deduct = new DefaultRedisScript<>();
        deduct.setLocation(new ClassPathResource("lua/seckill.lua"));
        deduct.setResultType(Long.class);
        this.seckillScript = deduct;

        DefaultRedisScript<Long> restore = new DefaultRedisScript<>();
        restore.setLocation(new ClassPathResource("lua/seckill_restore.lua"));
        restore.setResultType(Long.class);
        this.restoreScript = restore;
    }

    /**
     * 抢一次：成功就返回，失败按原因抛业务异常（用户当场看到"已售罄/已达限购"，不需要轮询）。
     *
     * @param endTimeMillis 活动结束时间（毫秒），用来算资格集合的 TTL——活动结束后 Redis 自己清理
     */
    public void deduct(Long activityId, Long userId, long endTimeMillis) {
        long ttlSeconds = Math.max(MIN_QUALIFY_TTL_SECONDS,
                (endTimeMillis - System.currentTimeMillis()) / 1000);
        Long code = redis.execute(seckillScript,
                List.of(RedisKeyConstants.seckillStock(activityId), RedisKeyConstants.seckillBought(activityId)),
                String.valueOf(userId), String.valueOf(ttlSeconds));

        if (code == null) {
            // 脚本没返回结果（连接异常等）：按"没抢到"处理，绝不能当成功往下走
            throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
        }
        if (code == 1L) {
            throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
        }
        if (code == 2L) {
            throw new BusinessException(ErrorCode.SECKILL_DUPLICATE);
        }
    }

    /**
     * 回补 Redis：<b>Redis 已扣但订单最终没建出来/被取消</b>时用
     * （名额还给库存、资格从集合里摘掉，用户还能再抢）。
     *
     * <p>★这里只处理 Redis。课程服务的 DB {@code remain} 由
     * {@link SeckillQuotaRestorer} 在同一个业务动作里一起回补；
     * 两处都做，才不会出现“Redis 有库存、DB 已经卖光”的永久少卖。</p>
     *
     * <p>★两条命令用一段 Lua 原子执行：只还库存不摘资格，用户还是抢不了；
     * 只摘资格不还库存，名额少一个。Lua 保证不会出现中间态。
     * 它仍然和课程 DB 的 remain 不在一个原子域里，所以调用方还要单独回补 DB。</p>
     */
    public void restore(Long activityId, Long userId) {
        redis.execute(restoreScript,
                List.of(RedisKeyConstants.seckillStock(activityId),
                        RedisKeyConstants.seckillBought(activityId)),
                String.valueOf(userId));
    }
}
