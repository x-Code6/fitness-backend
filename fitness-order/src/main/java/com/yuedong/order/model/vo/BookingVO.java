package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单视图对象（列表页与详情页共用）。
 *
 * <p>★v2 的三个设计点：</p>
 * <ol>
 *   <li><b>状态成对给出</b>：{@code status}（枚举码，前端做逻辑分支）+
 *       {@code statusText}（中文文案，前端直接渲染）。只给码就会让前端维护一份字典表，
 *       后端改文案前端永远慢一拍；只给文案则前端无法判断分支。</li>
 *   <li><b>金额同时给数值和展示串</b>：列表要比较/合计用数值，详情页要显示用字符串。
 *       只在后端格式化一次，不会出现三个页面三套小数位。</li>
 *   <li><b>{@code actions} 由后端算</b>：能不能支付/取消/评价/再次购买，
 *       取决于"订单状态 + 凭证状态 + 剩余次数 + 有效期"四个条件。
 *       前端重复实现这套规则，规则一改就要同步 N 个页面，是本项目最容易出问题的地方。
 *       这里先给出订单轴能单独决定的三个动作；
 *       <b>BOOK / RATE 需要凭证与评价数据，等凭证分支接上后再补</b>（不提前塞假值）。</li>
 * </ol>
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

    /** 这一单给几次（TIMES） */
    private Integer timesGranted;

    /** 买了几份 */
    private Integer quantity;

    private BigDecimal originalAmount;

    private BigDecimal discountAmount;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** 实付展示串，如 "¥68.00" */
    private String payAmountText;

    /** 原价展示串，秒杀单要显示划线价 */
    private String originalAmountText;

    /** 用的哪张券；null = 没用券 */
    private String couponTitle;

    private String status;

    private String statusText;

    /** 是否秒杀单。前端据此显示"限时特惠"角标 */
    private Boolean fromSeckill;

    /** 下单时间（后端已格式化，前端不解析时区） */
    private String createdAtText;

    private String payTimeText;

    /** 取消原因，如"超时未支付，自动取消" */
    private String cancelReason;

    /**
     * 可执行动作：{@code PAY / CANCEL / DELETE}。
     * <p>★BOOK（去约教练）与 RATE（去评价）依赖凭证与评价数据，在后续分支接入；
     * 现在塞进去会让前端做出点不动的按钮。</p>
     */
    private List<String> actions;
}
