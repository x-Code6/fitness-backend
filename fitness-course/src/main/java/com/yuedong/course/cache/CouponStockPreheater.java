package com.yuedong.course.cache;

import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.course.model.entity.Coupon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 秒杀券库存预热/补建。
 *
 * <p>★<b>绝不删 {@code coupon:got} 计数</b>：那会把"每人限领 N 张"的资格清空，
 * 领过的人能再领一遍——和秒杀里"重复预热不能删 bought"是同一个坑。</p>
 *
 * <p>★领取窗口内用 SETNX：Redis 重启/多实例重复预热时只补缺，
 * 不覆盖正在扣减的库存；窗口外（还没开抢）用 SET，让管理员改的总量生效。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponStockPreheater {

    private final StringRedisTemplate redis;

    public void preheat(Coupon coupon) {
        if (coupon.getReceiveEndTime() == null) {
            log.warn("券没有领取截止时间，跳过预热: couponId={}", coupon.getId());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long ttl = Math.max(60, Duration.between(now, coupon.getReceiveEndTime()).toSeconds());
        String key = RedisKeyConstants.couponStock(coupon.getId());
        String stock = String.valueOf(coupon.getRemain() == null ? 0 : coupon.getRemain());
        boolean receiving = coupon.getReceiveStartTime() != null
                && !now.isBefore(coupon.getReceiveStartTime())
                && !now.isAfter(coupon.getReceiveEndTime());

        try {
            if (receiving) {
                Boolean created = redis.opsForValue().setIfAbsent(
                        key, stock, Duration.ofSeconds(ttl));
                if (Boolean.TRUE.equals(created)) {
                    log.info("券库存已补建（领取中，未覆盖已有库存）: couponId={}, stock={}",
                            coupon.getId(), stock);
                }
            } else {
                redis.opsForValue().set(key, stock, Duration.ofSeconds(ttl));
                log.info("券库存已预热: couponId={}, stock={}", coupon.getId(), stock);
            }
        } catch (Exception e) {
            // 预热失败不能阻断管理员保存；下一分钟的补偿任务会再补
            log.error("券库存预热失败: couponId={}", coupon.getId(), e);
        }
    }
}
