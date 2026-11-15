package com.yuedong.order.model.vo;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * 一张凭证的处理结果，只用于调用方发不同文案。
 */
@Getter
public class EntitlementExpireResultVO {

    /** 是否真的完成了"ACTIVE → EXPIRED"（false = 已被别的实例/上一轮处理） */
    private final boolean expired;

    /** 已使用次数，用来区分"没用过"和"用过不退" */
    private final int usedTimes;

    /** 本次退款金额；0 表示没有退款 */
    private final BigDecimal refundAmount;

    private EntitlementExpireResultVO(boolean expired, int usedTimes, BigDecimal refundAmount) {
        this.expired = expired;
        this.usedTimes = usedTimes;
        this.refundAmount = refundAmount;
    }

    public static EntitlementExpireResultVO skipped() {
        return new EntitlementExpireResultVO(false, 0, BigDecimal.ZERO);
    }

    public static EntitlementExpireResultVO expiredNoRefund(int usedTimes) {
        return new EntitlementExpireResultVO(true, usedTimes, BigDecimal.ZERO);
    }

    public static EntitlementExpireResultVO refunded(BigDecimal refundAmount) {
        return new EntitlementExpireResultVO(true, 0, refundAmount);
    }
}
