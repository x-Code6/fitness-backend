package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 凭证视图（"我的凭证"列表、预约页权益条、订单详情嵌套都用它）。
 *
 * <p>★字段名以前端 Mock 为准（{@code fitness-web/src/api/mock/handlers.js#entitlementVO}），
 * 不是以 docs 4.3.3 的表格为准 —— 两端已经按 Mock 写完，后端改名就等于把联调成本推给前端。</p>
 *
 * <p>有意不给的三个字段，理由写在这里免得被当成漏做：</p>
 * <ul>
 *   <li>{@code tone}（徽章颜色）：色彩是视觉层，前端的 {@code utils/constants.js}
 *       已经有 status→tone 的映射表，后端再给一份就是两处真相；</li>
 *   <li>{@code coverColor}/{@code courseType}（卡片底色与分类）：数据在 yd_course，
 *       凭证表里只有课程名快照。要补就得给订单服务加一次批量 Feign，
 *       为两个装饰字段跨服务取数不值，前端按 courseId 自己兜默认色。</li>
 * </ul>
 */
@Data
@Builder
public class EntitlementVO {

    /** 凭证 id。★预约接口回传的是它，不是 orderNo */
    private Long entitlementId;

    /** 出票订单号，凭证要能跳回订单 */
    private String orderNo;

    private Long courseId;

    private String courseName;

    private Long storeId;

    /** ★凭证认店：只能在购买门店约教练，这行字必须显示给用户 */
    private String storeName;

    /** 一节课几小时，预约页格子分组用 */
    private Integer lessonHours;

    /** TIMES / UNLIMITED */
    private String saleType;

    /** 总次数；不限次为 null */
    private Integer totalTimes;

    private Integer usedTimes;

    /** 剩余次数；不限次为 null（前端显示"不限"） */
    private Integer remainTimes;

    /** 是否不限次。给布尔是为了让前端只写一个三元，不用再比字符串 */
    private Boolean unlimited;

    /** 有效期时间，格式化字符串；永久有效时为 null（★不返回 9999 年这种魔法值） */
    private String validUntil;

    /** 有效期文案："2026-11-13 到期" / "长期有效" */
    private String validUntilText;

    /** 凭证轴状态 */
    private String status;

    private String statusText;

    /**
     * 现在能不能拿去预约。★后端算：状态 ACTIVE + 有剩余次数（或不限次）+ 未到有效期，
     * 三个条件里有两个是 NULL 语义判断，前端拿不到数据库约定，自己拼一定会拼错。
     */
    private Boolean canBook;

    /**
     * 副标题：同名课多张凭证的区分信息，形如"订单尾号 0003 · 2026-10-12 购买"。
     * <p>★这是 D4（多份购买不合并凭证）的可见代价：买两次就在"待使用"里出现两条同名课，
     * 不给区分信息用户会以为是系统 bug。</p>
     */
    private String subTitle;
}
