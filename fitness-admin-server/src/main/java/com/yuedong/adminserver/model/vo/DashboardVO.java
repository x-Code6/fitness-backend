package com.yuedong.adminserver.model.vo;

import com.yuedong.order.api.dto.AdminStatDTO;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据看板一页数据（三个服务的数字拼在一起）。
 *
 * <p>★字段名与前端 Mock 一致（{@code upcoming} 而不是 upcomingCount）：
 * 管理端页面已经按这份名字写完了，BFF 是聚合层，
 * 让聚合层去对齐展示层是最省两侧改动量的方向。</p>
 *
 * <p>{@code todaySlotTotal} 来自课程服务（教练工时算得出分母）、
 * {@code todaySlotUsed} 来自订单服务（占用表算得出分子）——
 * <b>这一对比值本身没有业务规则</b>，所以可以在 BFF 里做；
 * 一旦某天要在这里判"占用率超过 80% 要标红"，那就得挪回 order 服务，
 * 因为那已经是业务口径了。</p>
 */
@Data
@Builder
public class DashboardVO {

    private Integer todayOrderCount;

    private BigDecimal todayAmount;

    private BigDecimal refundedAmount;

    /** 今日应开放的时段格子数（分母，course 侧） */
    private Integer todaySlotTotal;

    /** 今日已被占用的格子数（分子，order 侧） */
    private Integer todaySlotUsed;

    /** 待上课的预约张数 */
    private Integer upcoming;

    private Integer memberCount;

    private Integer activeEntitlement;

    private Integer expiringSoon;

    private Integer courseCount;

    private Integer coachCount;

    private List<AdminStatDTO.HotCourse> hotCourses;

    private List<AdminStatDTO.TrendPoint> trend;
}
