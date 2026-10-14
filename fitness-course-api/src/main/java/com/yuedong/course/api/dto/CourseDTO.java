package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 课程简况 DTO：order 服务建单时跨服务取课程快照用。
 *
 * <p>★v2 删掉的字段：{@code coach}/{@code coachTitle}（教练已成实体，且订单快照要的是
 * "约了谁"，那是预约时才确定的，不属于购买）、
 * {@code nextTime}（"今天 19:00"这种文案，正是旧代码里让秒杀订单显示"预约制"的元凶）、
 * {@code capacity}/{@code booked}（座位语义已废弃）、
 * {@code storeName} 从课程表来 → 改为按 {@code storeId} 从 {@code t_course_store} 来。</p>
 *
 * <p>{@code @NoArgsConstructor + @AllArgsConstructor} 是跨服务 DTO 的硬要求，
 * 只有 @Builder 会让消费方 Jackson 反序列化失败。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseDTO {

    private Long id;

    /** 课程名：订单要存成快照 */
    private String name;

    /** SALE / CONTENT。★购买链路只允许 SALE，由调用方判断并拒绝 */
    private String courseKind;

    private String type;

    /** 一节课几小时。订单存快照，前端显示"2 小时/节" */
    private Integer lessonHours;

    /** TIMES / UNLIMITED */
    private String saleType;

    /**
     * 封面渐变色下标 0~5。
     * <p>★带上它的理由很具体：购物车页要把每条画成和列表一样的卡片，
     * 而卡片没有图时靠这个下标定颜色。为这一个装饰字段单独加一次 RPC 不值，
     * 但批量快照里顺手就有，不必让前端为了颜色再查一遍详情。</p>
     */
    private String coverColor;

    /** 买一单给几次（TIMES）；UNLIMITED 为 null */
    private Integer timesPerBuy;

    /** 净时长（分钟） */
    private Integer duration;

    private Integer calories;

    /** 原价（单节价）。★金额用 BigDecimal，不用 double：浮点会算出 0.30000000000000004 */
    private BigDecimal price;

    /** 购买门店 id */
    private Long storeId;

    /** 购买门店名，订单存快照用 */
    private String storeName;

    /**
     * 该课在此门店当前是否在可售窗口内。
     * <p>★判空在这里做掉，是为了让 order 服务不必再关心时间窗细节：
     * 一门课"存在"不等于"能卖"，长期在售、已停售、还没开售是三种不同状态。</p>
     */
    private Boolean onSale;

    // =====================================================================
    // 可使用窗口（t_course_store 的第二层时间）。★这四列只干一件事：
    // 让 order 服务在支付成功那一刻算出凭证的 valid_until。
    // 不在课程服务算好直接给 validUntil，是因为"起算点"是支付时刻——
    // 课程服务不知道用户什么时候付款，把 now() 传过去反而把两个服务的时钟耦在一起。
    // =====================================================================

    /** FOREVER 永不过期 / DAYS 自支付起 N 天 / RANGE 固定窗口。取值见 valid_type 列注释 */
    private String validType;

    /** valid_type=DAYS 时生效：领后 N 天内可用 */
    private Integer validDays;

    /** valid_type=RANGE 时生效：可使用起始（凭证不校验起始，只校验截止） */
    private LocalDateTime useStartTime;

    /** valid_type=RANGE 时生效：可使用截止。★FOREVER/DAYS 时为 null，不代表"永久"，看 validType */
    private LocalDateTime useEndTime;

    /**
     * 整块规则条目（<b>由课程域的策略生成后随快照带出</b>）。
     * <p>★为什么在课程侧算好而不是让 order 自己拼：规则文案是课程域的知识
     * （几小时一节、一单几次、过期怎么处理），order 再写一份就是两处文案，
     * 改一处忘另一处是必然结果。时段页要显示规则，就从这里取。</p>
     */
    private List<String> ruleTexts;

    /** 一句话规则摘要，同上一样的理由 */
    private String ruleSummary;
}
