package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单详情（第 8 条需求："像真实电商一样有列表和详情"）。
 *
 * <p>★与列表项 {@link BookingVO} 是两个对象而不是一个加字段：
 * 详情要嵌凭证与其下的预约记录、要门店地址、要每一格的金额明细，
 * 列表一次返回十几条，带这些字段就是白占带宽。
 * <b>同一个实体在不同页面要的是不同的信息量，就该有两个 VO。</b></p>
 */
@Data
@Builder
public class BookingDetailVO {

    private String orderNo;


    /** 是否来自购物车合并下单（前端据此显示"来自购物车"标签） */
    private Boolean isFromCart;

    private String status;

    private String statusText;

    // ---------- 商品行 ----------

    private Long courseId;

    private String courseName;

    private String courseKind;

    /** 分类标签（卡片底色与图标按它选） */
    private String courseType;

    /** 封面渐变色下标。★来自课程快照，取不到时前端回退默认色 */
    private String coverColor;

    private Integer lessonHours;

    private String saleType;

    /** "按次购买" / "不限次" */
    private String saleTypeText;

    private Integer timesGranted;

    private Integer quantity;

    // ---------- 门店 ----------

    private Long storeId;

    private String storeName;

    /**
     * 门店地址。★来自一次跨服务查询，取不到给 null、前端不显示这一行。
     * <p>"到店上课"的地址是详情页里唯一会影响用户行动的信息，
     * 但取不到它不该让整单打不开——所以是可选字段而不是失败条件。</p>
     */
    private String storeAddress;

    // ---------- 金额 ----------

    private BigDecimal unitPrice;

    private String unitPriceText;

    private BigDecimal originalAmount;

    private String originalAmountText;

    private BigDecimal discountAmount;

    private String discountAmountText;

    private BigDecimal payAmount;

    /** 累计已退款金额；0 = 没退过，部分退款时订单仍是 PAID */
    private BigDecimal refundAmount;

    private String payAmountText;

    /** 退款展示串，如 "¥75.00"；没退过为 null */
    private String refundAmountText;

    private String couponTitle;

    // ---------- 时间与原因 ----------

    private String createdText;

    private String paidText;

    private String canceledText;

    private String refundText;

    private String cancelReason;

    // ---------- 履约 ----------

    /**
     * 这张订单出的凭证，每张下面挂它的预约记录。
     * <p>★嵌套结构让用户一眼看懂"我买了 3 次、约了 1 次、还剩 2 次"，
     * 这正是把订单/凭证/预约拆成三条轴的<b>可见收益</b>。</p>
     */
    private List<EntitlementVO> entitlements;

    /** 是否已评价过。★后端给布尔，比让前端进详情页再单独调一次校验接口省一次往返 */
    private Boolean rated;

    /** {@code PAY / CANCEL / BOOK / RATE / AGAIN}，后端算 */
    private List<String> actions;
}
