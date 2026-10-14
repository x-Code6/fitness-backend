package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单列表项视图（★字段名对齐前端 Mock 的 {@code bookingListItemVO}）。
 *
 * <p>★v2 的三个设计点：</p>
 * <ol>
 *   <li><b>状态成对给出</b>：{@code status}（枚举码，前端做逻辑分支）+
 *       {@code statusText}（中文文案，前端直接渲染）。只给码就会让前端维护一份字典表，
 *       后端改文案前端永远慢一拍；只给文案则前端无法判断分支。</li>
 *   <li><b>金额同时给数值和展示串</b>：列表要比较/合计用数值，详情页要显示用字符串。
 *       只在后端格式化一次，不会出现三个页面三套小数位。</li>
 *   <li><b>{@code actions} 由后端算</b>：能不能支付/取消/去约/评价/再来一单，
 *       取决于"订单状态 + 凭证状态 + 剩余次数 + 有没有评过"这套组合。
 *       前端重复实现一遍就是四个出错点，规则一改要同步 N 个页面。</li>
 * </ol>
 *
 * <p>不给的字段：{@code tone}（徽章颜色，前端 {@code utils/constants.js} 已有映射表）、
 * {@code coverColor}/{@code courseType}（数据在 yd_course，为两个装饰字段跨服务取数不值）。</p>
 */
@Data
@Builder
public class BookingVO {

    /** 对外订单号。★不给自增 id：会泄露业务量，且跨库关联要用它 */
    private String orderNo;

    /** 购物车合并结算的组号；null = 单买 */
    private String parentNo;

    private Long courseId;

    private String courseName;

    /** SALE / CONTENT */
    private String courseKind;

    private Long storeId;

    private String storeName;

    /** "2 小时/节"这类展示文案由 lessonHours 拼出来 */
    private Integer lessonHours;

    /** TIMES / UNLIMITED */
    private String saleType;

    /** 这一单给几次（TIMES）；UNLIMITED 为 null */
    private Integer timesGranted;

    /** 买了几份 = 出了几张凭证 */
    private Integer quantity;

    private BigDecimal originalAmount;

    private BigDecimal discountAmount;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** 实付展示串，如 "¥68.00" */
    private String payAmountText;

    /** 原价展示串，秒杀单要显示划线价 */
    private String originalAmountText;

    private String discountAmountText;

    /** 用的哪张券；null = 没用券 */
    private String couponTitle;

    private String status;

    private String statusText;

    /** 是否秒杀单。前端据此显示"限时特惠"角标 */
    private Boolean fromSeckill;

    /** 下单时间（后端已格式化，前端不解析时区） */
    private String createdText;

    private String paidText;

    private String refundText;

    /** 取消原因，如"超时未支付，自动取消" */
    private String cancelReason;

    /**
     * 凭证摘要，形如"2 张凭证 · 可用 1 张"。
     * <p>★只有已支付的单才有值（没付款就没有凭证），其余给空串而不是 null，
     * 前端可以直接渲染不用判空。</p>
     */
    private String entitlementText;

    /** 副标题：购物车合并下单时给"购物车合并下单"，单买给空串 */
    private String subTitle;

    /**
     * 可执行动作：{@code PAY / CANCEL / BOOK / RATE / AGAIN}。
     * <p>★BOOK 依赖凭证数据（本服务同库，一次 IN 批量取，不在循环里查）；
     * RATE 还要看有没有评过，在评价分支接入后补上。</p>
     */
    private List<String> actions;
}
