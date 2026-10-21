package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 下单页试算结果（对应前端 {@code GET /booking/preview}）。
 *
 * <p>★这个页面上<b>所有要素钱的数字都来自后端</b>：前端只负责显示，
 * 用户改一次份数、换一张券就重新打一次这个接口。
 * 前端不参与任何金额计算，更不能把金额传回来——
 * 传回来就等于把定价权交给了抓包工具。</p>
 *
 * <p>每个金额都配一个 {@code *Text}：分数的显示格式（保留几位、带不带 ¥）
 * 属于界面口径，由后端一次拼好，免得前端各处自己 toFixed 出四种样子。</p>
 */
@Data
@Builder
public class BookingPreviewVO {

    // ---------- 买的是什么（快照，够页面渲染，不让前端再查一次课程详情）----------

    /**
     * <b>预分配订单号，同时就是这次下单的令牌。</b>
     * <p>★前端必须存下它、并在 {@code POST /booking/create} 里<b>原样带回</b>。
     * 服务端已在 Redis 记下落这个号的那枚令牌（10 分钟有效），
     * 提交时原子取回并删除——取不到就是重复提交或已过期。
     * 所以同一枚令牌只能换来一张订单，用户连点、前端超时重发都只生效一次。</p>
     * <p>★用户改了份数或换了券，只是重新拉一次 preview、<b>拿到一个新号</b>，
     * 用最新那个去提交即可；旧号会自己过期，不需要前端做任何清理。</p>
     */
    private String orderNo;

    private Long courseId;

    private String courseName;

    private Long storeId;

    private String storeName;

    /** 一节课几小时，页面要显示"2 小时/节" */
    private Integer lessonHours;

    /** TIMES 按次 / UNLIMITED 有效期内不限次 */
    private String saleType;

    /** TIMES 时一单给几次；UNLIMITED 为 null */
    private Integer timesGranted;

    // ---------- 份数 ----------

    private Integer quantity;

    /**
     * 份数上限，★由后端下发而不是前端写死。
     * <p>前端加号到这个数就禁用。以前服务端是静默 {@code Math.min} 截断，
     * 用户点 20 份、后端按 10 份建单，不报错也不提示；现在超限直接报错，
     * 而这个数是同一个常量来的，两边不会各写一份。</p>
     */
    private Integer maxQuantity;

    // ---------- 金额（全部后端算）----------

    private BigDecimal unitPrice;

    private String unitPriceText;

    /** 原价小计 = 单价 × 份数 */
    private BigDecimal originalAmount;

    private String originalAmountText;

    /** 券抵扣额。所选券不可用时为 0 */
    private BigDecimal discountAmount;

    private String discountAmountText;

    /** 实付 = 原价 - 抵扣，最低 0。★这是唯一有效的成交金额 */
    private BigDecimal payAmount;

    private String payAmountText;

    // ---------- 券 ----------

    /** 回显用户当前选中的券记录 id；没用券为 null */
    private Long couponRecordId;

    /** 选中的这张券此刻是否可用 */
    private Boolean couponUsable;

    /** 可用时的券名（页面显示"已选：新人无门槛券 -¥10"） */
    private String couponTitle;

    /** 不可用的原因（页面直接显示"还差 50 元可用"，用户才知道该做什么） */
    private String couponReason;

    /** 这个份数下，用户手里所有未使用券的判定结果，可用的排在前面 */
    private List<CouponOptionVO> coupons;

    // ---------- 买完会得到什么 ----------

    /** 付款后出几张凭证（= 份数，多份不合并） */
    private Integer entitlementCount;

    /** 凭证说明文案，如"共 2 张凭证，每张可上 1 次" */
    private String entitlementText;

    /** 有效期与使用规则摘要（来自课程侧规则说明，★不是订单服务自己编的） */
    private String ruleSummary;

    private List<String> ruleTexts;

    // ==================== 秒杀态（★份数与选券两个控件由这三个字段决定要不要显示）====================

    /** 秒杀活动 id。非 null = 这一单是秒杀单，提交时要原样带回 */
    private Long activityId;

    /** 是否秒杀单。前端据此把份数锁成 1、把选券入口整行隐藏 */
    private Boolean seckill;

    /** 活动结束时间（毫秒）。确认页倒计时用；到点后提交会被服务端拒掉 */
    private Long seckillEndTime;
}
