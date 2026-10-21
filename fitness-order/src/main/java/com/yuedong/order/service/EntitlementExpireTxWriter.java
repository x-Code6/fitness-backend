package com.yuedong.order.service;

import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.mapper.EntitlementMapper;
import com.yuedong.order.model.entity.Entitlement;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 凭证过期的事务边界：<b>置过期 + 按张退款 + 累加订单退款额</b>，要么都成功，要么都回滚。
 *
 * <p>★为什么退款要做到"凭证"这一级：一张订单买 N 份就是 N 张独立凭证。
 * 旧逻辑是"任意一张没用的凭证过期 → 整单 REFUNDED"，于是买 2 张、用 1 张、
 * 另 1 张过期时，用户上了课还能拿全款。现在改成：</p>
 * <ul>
 *   <li>每张凭证在出票时记下自己分摊到的实付金额 {@code paid_amount}；</li>
 *   <li>某张凭证过期且一次没用 → 退它自己的 {@code paid_amount}；</li>
 *   <li>订单侧累计 {@code refund_amount}，累计达到 {@code pay_amount} 才置 REFUNDED，
 *       否则订单保持 PAID（部分退款）。</li>
 * </ul>
 *
 * <p>★为什么单独一个 Bean：Spring 事务靠代理生效，在
 * {@link com.yuedong.order.service.impl.EntitlementServiceImpl} 里自调用不走代理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementExpireTxWriter {

    private final EntitlementMapper entitlementMapper;
    private final BookingMapper bookingMapper;

    /**
     * 处理一张到期凭证。
     *
     * @param entitlement 到期候选（调用方已经排除了"还有 BOOKED 预约"的凭证）
     * @param now         当前时间，由调用方传入，便于测试
     * @return 本张凭证的处理结果，调用方据此发通知
     */
    @Transactional(rollbackFor = Exception.class)
    public ExpireResult expireAndRefund(Entitlement entitlement, LocalDateTime now) {
        // 改：ACTIVE → EXPIRED。带状态条件，多实例/重跑只有第一个能改成功。
        int expiredRows = entitlementMapper.expireOne(entitlement.getId(), now);
        if (expiredRows == 0) {
            return ExpireResult.skipped();
        }

        int usedTimes = entitlement.getUsedTimes() == null ? 0 : entitlement.getUsedTimes();
        if (usedTimes > 0) {
            // 用过一点的凭证：按 D7 规则剩余不退，只置过期
            return ExpireResult.expiredNoRefund(usedTimes);
        }

        // 改：记下这张凭证已退款。条件是"已过期 + 一次没用 + 没退过"，保证只退一次。
        int refundedRows = entitlementMapper.markRefunded(entitlement.getId(), now);
        if (refundedRows == 0) {
            // 已经退过（重复扫描/人工重放），本次只算过期，不再退钱
            return ExpireResult.expiredNoRefund(0);
        }

        BigDecimal refundAmount = entitlement.getPaidAmount() == null
                ? BigDecimal.ZERO : entitlement.getPaidAmount();

        // 有实际金额才累加订单退款额；0 元券/0 元单只置过期，不产生退款流水
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            int orderRows = bookingMapper.addRefundAmount(entitlement.getOrderNo(), refundAmount, now);
            if (orderRows == 0) {
                // 订单不是 PAID：可能已经全额退款，或数据来自旧逻辑（旧逻辑会整单退）。
                // 这时凭证本身仍然要置过期，但不能每小时回滚重试——订单已经是终态，
                // 没有第二笔钱可退。金额以订单已有的终态为准。
                log.warn("订单不是 PAID，无法累加退款，凭证仍置过期: orderNo={}, entitlementId={}",
                        entitlement.getOrderNo(), entitlement.getId());
                return ExpireResult.expiredNoRefund(0);
            }
        }

        log.info("凭证过期并按张退款: entitlementId={}, orderNo={}, 退款={}",
                entitlement.getId(), entitlement.getOrderNo(), refundAmount);
        return ExpireResult.refunded(refundAmount);
    }

    /** 一张凭证的处理结果，只用于调用方发不同文案 */
    @Getter
    public static class ExpireResult {

        /** 是否真的完成了"ACTIVE → EXPIRED"（false = 已被别的实例/上一轮处理） */
        private final boolean expired;

        /** 已使用次数，用来区分"没用过"和"用过不退" */
        private final int usedTimes;

        /** 本次退款金额；0 表示没有退款 */
        private final BigDecimal refundAmount;

        private ExpireResult(boolean expired, int usedTimes, BigDecimal refundAmount) {
            this.expired = expired;
            this.usedTimes = usedTimes;
            this.refundAmount = refundAmount;
        }

        public static ExpireResult skipped() {
            return new ExpireResult(false, 0, BigDecimal.ZERO);
        }

        public static ExpireResult expiredNoRefund(int usedTimes) {
            return new ExpireResult(true, usedTimes, BigDecimal.ZERO);
        }

        public static ExpireResult refunded(BigDecimal refundAmount) {
            return new ExpireResult(true, 0, refundAmount);
        }
    }
}
