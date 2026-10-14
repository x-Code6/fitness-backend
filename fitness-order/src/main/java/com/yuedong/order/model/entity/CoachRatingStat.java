package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 教练评分汇总，结构同 {@link StoreRatingStat} */
@Data
@TableName("t_coach_rating_stat")
public class CoachRatingStat {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("coach_id")
    private Long targetId;

    @TableField("rating_sum")
    private Integer ratingSum;

    @TableField("rating_count")
    private Integer ratingCount;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
