package com.yuedong.order.model.vo.slot;

import lombok.Builder;
import lombok.Data;

/**
 * 时段页的"我的凭证"条（页面级信息，只出现一次）。
 *
 * <p>★多张同名凭证时选哪张返回给前端？按<b>最早过期的那张先消耗</b>
 * （{@code validUntil} 升序，null 排最后），这对用户最有利：
 * 反正都要过期，先用快要过期的那张，长期有效的那张留着。</p>
 */
@Data
@Builder
public class SlotMyVO {

    /** 这门课在这个店买过吗（一张都没买是 false，用于"去买课"的引导文案） */
    private Boolean bought;

    /** 建议使用的凭证 id。★创建预约要回传它；没有可用凭证时为 null */
    private Long entitlementId;

    private Integer totalTimes;

    private Integer usedTimes;

    /** 剩余次数；不限次为 null */
    private Integer remainTimes;

    /** 是否不限次 */
    private Boolean unlimited;

    /** 有效期文案："2026-11-13 前可用" / "长期有效" / 没买课时给"尚未购买本课程" */
    private String validUntilText;

    /**
     * 后端综合判断出来的"这一页到底能不能约"。
     * <p>★四个条件：有凭证 + 状态 ACTIVE + 有剩余次数（或不限次）+ 未过期。
     * 任缺其一，页面上所有格子都该是灰的且 reason=NO_ENTITLEMENT —
     * 这个字段就是那个开关，前端不需要自己去凑条件。</p>
     */
    private Boolean canBook;

    /**
     * 可用凭证张数（&gt;1 时页面提示"你有 3 张可用凭证，本次将使用其中一张"）。
     * <p>★D4 不合并凭证的可见代价：用户会看到多张同名凭证，
     * 不给个总数他不知道自己有得选。</p>
     */
    private Integer multiCount;
}
