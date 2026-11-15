package com.yuedong.course.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.course.cache.CouponStockPreheater;
import com.yuedong.course.mapper.CouponMapper;
import com.yuedong.course.model.entity.Coupon;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀券库存 key 补偿任务。
 *
 * <p>券以前只在管理端保存时预热一次，Redis 重启/被清后 Lua 把缺键当 0，
 * 用户永远领不到，直到管理员再保存一次。这里每分钟扫一次正在领取的秒杀券，
 * 只补不存在的 key，已有库存绝不覆盖。</p>
 */
@Component
@RequiredArgsConstructor
public class CouponStockRepairTask {

    private final CouponMapper couponMapper;
    private final CouponStockPreheater couponStockPreheater;

    @Scheduled(fixedDelay = 60_000)
    public void repairSeckillStockKeys() {
        LocalDateTime now = LocalDateTime.now();
        List<Coupon> coupons = couponMapper.selectList(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, 1)
                .eq(Coupon::getCouponType, "SECKILL")
                .le(Coupon::getReceiveStartTime, now)
                .ge(Coupon::getReceiveEndTime, now));
        for (Coupon coupon : coupons) {
            couponStockPreheater.preheat(coupon);
        }
    }
}
