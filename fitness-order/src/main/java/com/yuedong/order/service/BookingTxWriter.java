package com.yuedong.order.service;

import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CouponClient;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.model.entity.Booking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 建单的事务段：写订单 + 核销优惠券，<b>要么都成、要么都不成</b>。
 *
 * <p>★为什么要单独拎一个类出来，而不是在 {@code BookingServiceImpl.create} 上直接标
 * {@code @Transactional}：Spring 的事务是靠代理实现的，
 * 同一个类里 {@code this.xxx()} 自调用<b>不走代理</b>，注解等于白写。
 * 这一点和 point 模块的 {@code PointsTxWriter} 是同一个原因、同一个解法。</p>
 *
 * <p>★事务包住这两步的意义：以前 create 没有事务，注释里却写着
 * "核销失败则本地订单随之回滚"——那是假的。insert 先提交了，
 * 券核销失败抛异常后库里会留下一张引用着<b>未核销券</b>的孤儿待支付单
 * （coupon_record_id 和 coupon_title 都已落库，券却还是 UNUSED 状态）。</p>
 *
 * <p>★延迟消息和站内通知<b>不放进来</b>：发出去的消息收不回，
 * 让它参与事务就等于"消息发成功了但订单回滚了"→ 一条永远不会命中的超时取消；
 * 反过来若把它们放事务内、发 MQ 失败又会把一张本该成功的订单打回。
 * 所以顺序是：事务先提交，再发消息（见 {@code BookingServiceImpl#create}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingTxWriter {

    private final BookingMapper bookingMapper;
    private final CouponClient couponClient;

    /**
     * 落库并把券用掉。
     *
     * @param booking        已经算好金额、填好快照的订单对象（本方法只负责写）
     * @param couponRecordId 用户选的券；null = 没用券，跳过核销
     */
    @Transactional(rollbackFor = Exception.class)
    public void write(Booking booking, Long userId, Long couponRecordId) {

        // 增：写订单主表。此时还没提交，后面任何一步失败都会把它一起撤销
        bookingMapper.insert(booking);

        if (couponRecordId == null) {
            return;
        }

        // 跨服务核销券（UNUSED → USED，并把订单号绑上去）
        Result<Boolean> useResult = couponClient.use(couponRecordId, userId, booking.getOrderNo());
        if (useResult.getCode() != 0 || !Boolean.TRUE.equals(useResult.getData())) {
            log.error("优惠券核销失败，订单已回滚: userId={}, recordId={}, orderNo={}",
                    userId, couponRecordId, booking.getOrderNo());
            // 抛异常 → 上面那条 insert 回滚，库里不留孤儿单
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, "优惠券核销失败，请重新选择");
        }
    }
}
