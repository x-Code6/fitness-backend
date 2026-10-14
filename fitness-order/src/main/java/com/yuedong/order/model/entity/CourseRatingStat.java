package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 课程评分汇总，结构同 {@link StoreRatingStat}（三张表分开是因为它们各自被不同页面排序，不是一张表加 type 列） */
@Data
@TableName("t_course_rating_stat")
public class CourseRatingStat {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("course_id")
    private Long targetId;

    @TableField("rating_sum")
    private Integer ratingSum;

    @TableField("rating_count")
    private Integer ratingCount;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
