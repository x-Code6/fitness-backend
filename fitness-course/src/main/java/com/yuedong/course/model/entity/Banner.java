package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 首页活动轮播位。一条记录只指向一个来源：普通课程 / 秒杀活动 / 优惠券。
 */
@Data
@TableName("t_banner")
public class Banner {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    private String subtitle;

    @TableField("image_url")
    private String imageUrl;

    /** COURSE / SECKILL / COUPON */
    @TableField("link_type")
    private String linkType;

    /** 课程 id / 秒杀活动 id / 券 id */
    @TableField("target_id")
    private Long targetId;

    @TableField("sort_no")
    private Integer sortNo;

    /** 1 上架 / 0 下架 */
    private Integer status;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
