package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教练可授课范围（多对多关联表），对应 yd_course.t_coach_course。
 *
 * <p>两张表之间是多对多：一个教练教多门课，一门课有多个教练可教。
 * 关联表本身不带业务字段，只有两个外键 + 创建时间，这是纯关系表的标准形态。</p>
 *
 * <p>索引用途区分得很清楚（缺一不可）：</p>
 * <ul>
 *   <li>{@code uk_coach_course(coach_id, course_id)} → 防重复配课，
 *       并且最左前缀正好服务管理端"这个教练会教哪些课"；</li>
 *   <li>{@code idx_course(course_id)} → C 端最核心的查询"这门课在本店有哪些教练可约"。
 *       唯一键的最左前缀只顶 coach 那一头，course 那头必须单独建，
 *       否则这个查询就是全表扫描（和 yd_community.t_follow 那对索引同理）。</li>
 * </ul>
 */
@Data
@TableName("t_coach_course")
public class CoachCourse {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 教练 id */
    @TableField("coach_id")
    private Long coachId;

    /** 课程 id（管理端只能从"已存在的课程"里选，不允许手输课名） */
    @TableField("course_id")
    private Long courseId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
