package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
}
