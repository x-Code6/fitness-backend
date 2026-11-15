package com.yuedong.course.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.mapper.CouponMapper;
import com.yuedong.course.mapper.UserCouponMapper;
import com.yuedong.course.model.entity.Coupon;
import com.yuedong.course.model.entity.UserCoupon;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 秒杀券领取的事务边界：<b>写持券记录 + 扣 DB 库存</b>，要么都成功，要么都回滚。
 *
 * <p>★为什么单独一个 Bean：Spring 事务靠代理生效，在
 * {@link com.yuedong.course.service.impl.CouponServiceImpl} 里自调用
 * {@code this.xxx()} 不走代理，注解等于白写。</p>
 *
 * <p>★修复的 bug：以前是"先 insert 持券记录（自动提交）→ 再扣 DB remain"，
 * 第二步返回 0 时只回补了 Redis，已经插入的持券记录不会消失。
 * 结果就是用户看到"售罄"，卡包里却白得一张券，而且个人计数被减回去后还能继续领。</p>
 */
@Service
@RequiredArgsConstructor
public class CouponReceiveTxWriter {

    private final UserCouponMapper userCouponMapper;
    private final CouponMapper couponMapper;

    /**
     * 写一张秒杀券持券记录，并扣减 DB 库存。
     *
     * <p>顺序是"先插记录、再扣库存"：库存扣不动时抛异常，整个事务回滚，
     * 插入的持券记录也一起撤销。Redis 库存由调用方在事务失败后回补。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveSeckillRecord(Long userId, Long couponId) {
        // 增：持券记录。status=UNUSED，收到时间用于 DAYS 类型的有效期起算
        UserCoupon record = new UserCoupon();
        record.setUserId(userId);
        record.setCouponId(couponId);
        record.setStatus("UNUSED");
        record.setReceivedAt(LocalDateTime.now());
        userCouponMapper.insert(record);

        // 改：DB 账本 remain-1。remain>0 条件保证不会扣成负数；
        // 返回 0 说明 Redis 与 DB 已经不一致，按售罄处理并让整个事务回滚。
        int rows = couponMapper.deductRemain(couponId);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT, "优惠券库存不足");
        }
    }

    /**
     * 普通券领取：锁券模板行 → 查个人已领张数 → 插入持券记录，同一事务。
     *
     * <p>★普通券不限库存，但 limit_per_user > 0 时仍要限领。只做
     * {@code COUNT(*)} 再 INSERT，两个并发请求会同时看到 limit-1。
     * 锁同一张券的模板行把并发串行化，第二次 COUNT 能看到第一次的插入。</p>
     *
     * @param limitPerUser 0 = 不限领
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveNormalRecord(Long userId, Long couponId, int limitPerUser) {
        Coupon coupon = couponMapper.selectByIdForUpdate(couponId);
        if (coupon == null || !Integer.valueOf(1).equals(coupon.getStatus())) {
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, "优惠券已下架");
        }

        if (limitPerUser > 0) {
            Long got = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getCouponId, couponId));
            if (got != null && got >= limitPerUser) {
                throw new BusinessException(ErrorCode.COUPON_DUPLICATE,
                        "每人限领 " + limitPerUser + " 张");
            }
        }

        UserCoupon record = new UserCoupon();
        record.setUserId(userId);
        record.setCouponId(couponId);
        record.setStatus("UNUSED");
        record.setReceivedAt(LocalDateTime.now());
        userCouponMapper.insert(record);
    }
}
