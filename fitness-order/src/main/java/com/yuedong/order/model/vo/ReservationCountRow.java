package com.yuedong.order.model.vo;

import lombok.Data;

/**
 * 一个凭证被多少张预约用过（内部读结果）。
 *
 * <p>★整页一次 GROUP BY，★不在循环里逐条 COUNT：一页 10 张凭证就是 10 次查询。
 * 结果里<b>没有</b>"预约数为 0"的那些凭证（GROUP BY 只返回有行的分组），
 * 贴回 DTO 时给默认值 0。</p>
 */
@Data
public class ReservationCountRow {

    /** 凭证 id */
    private Long entitlementId;

    /** 未取消的预约张数 */
    private Integer reservationCount;
}
