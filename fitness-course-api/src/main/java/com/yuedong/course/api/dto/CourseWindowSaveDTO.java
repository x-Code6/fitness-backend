package com.yuedong.course.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保存某门课在某家店的窗口（{@code t_course_store} 的一行）。
 *
 * <p>★只能改<b>已存在</b>的关系行，不能新建：{@code id} 必传。
 * "这门课在哪家店卖"是 {@code /admin/course/assign-store} 的职责（整组覆盖多对多关系），
 * "在一家店怎么卖"才是这里。两个动作分开是因为它们的并发形状不同：
 * 前者先删后插、后者只改一行。合在一个接口里就会出现"保存窗口时顺手把别的门店配置清了"。</p>
 *
 * <p>★跨字段规则（结束必须晚于起始、DAYS 必须给天数、RANGE 必须给区间）
 * 全部留在服务层校验：注解表达不了条件必填，而"FOREVER 时前端不传 validDays"
 * 用注解写成 @NotNull 会直接判错。</p>
 */
@Data
public class CourseWindowSaveDTO {

    /** t_course_store 主键，必传 */
    @NotNull(message = "缺少窗口 id，请先在课程表单里保存门店关系")
    private Long id;

    /** ① 开售时间，必传 */
    @NotNull(message = "请设置开售时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime sellStartTime;

    /** ① 停售时间；为空表示长期在售 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime sellEndTime;

    /** ② FOREVER / DAYS / RANGE */
    @NotNull(message = "请选择凭证有效期类型")
    private String validType;

    /** validType=DAYS 时必填 */
    private Integer validDays;

    /** validType=RANGE 时必填（与 useEndTime 成对） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime useStartTime;

    /** validType=RANGE 时必填 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime useEndTime;

    /** 可售份数；为空表示不限 */
    private Integer stock;

    /** 1 在售 / 0 下架；不传保持原值 */
    private Integer status;
}
