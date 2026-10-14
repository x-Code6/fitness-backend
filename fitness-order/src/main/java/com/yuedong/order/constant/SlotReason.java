package com.yuedong.order.constant;

/**
 * 时段格子不可约的原因（<b>枚举而不是布尔</b>）。
 *
 * <p>★这是本项目 VO 设计里最值得讲的一条：布尔只能显示"不可约"，
 * 用户会反复点同一个格子并认定页面坏了。后端多给一个字符串，
 * 前端才能把"为什么点不了"直接写在格子上，省掉一整轮客服。</p>
 *
 * <p>六个取值的判断顺序（{@code SlotServiceImpl} 里严格按这个顺序覆盖）：
 * 教练当天不排班 &gt; 已过时间点 &gt; 被别人占 &gt; 被自己的预约占 &gt;
 * 空闲但整组放不下 &gt; 我根本没有可用凭证。★顺序错了就会出现
 * "明明已经过了这个点却提示已被预约"这类自相矛盾的文案。</p>
 */
public final class SlotReason {

    /** 教练当天不排班（{@code work_days} 不含这个星期），或已超出营业/工时区间 */
    public static final String CLOSED = "CLOSED";

    /** 这个整点已经过去了（查今天时的历史格子）。★前端契约里的第五种灰态，文档初稿没有 */
    public static final String PASSED = "PASSED";

    /** 已被<b>别人</b>预约（t_coach_busy 里有行且 user_id 不是自己） */
    public static final String BOOKED = "BOOKED";

    /** 被<b>自己</b>的预约占了。用户看到"我已约"才不会以为自己点错了 */
    public static final String SELF_USED = "SELF_USED";

    /** 物理上空着，但整组放不下 H 个连续格子（lessonHours&gt;1 时 D3 不可拆分的直接产物） */
    public static final String GROUP_UNAVAILABLE = "GROUP_UNAVAILABLE";

    /** 格子可约但<b>我没有可用凭证</b>（没买、用完或过期） */
    public static final String NO_ENTITLEMENT = "NO_ENTITLEMENT";

    private SlotReason() {
    }

    /** 原因码 → 提示文案。★文案仍留在后端：它是业务规则，前端各写一份一定会不一致 */
    public static String textOf(String reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case CLOSED -> "教练当天不排班";
            case PASSED -> "已过上课时间";
            case BOOKED -> "该时段已被预约";
            case SELF_USED -> "你已约了这个时段";
            case GROUP_UNAVAILABLE -> "连续时段不足，放不下完整一节";
            case NO_ENTITLEMENT -> "还没有可用凭证";
            default -> reason;
        };
    }
}
