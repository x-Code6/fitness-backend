package com.yuedong.course.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理端活动页列表项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BannerAdminDTO {

    private Long id;

    private String title;

    private String subtitle;

    private String imageUrl;

    /** COURSE / SECKILL / COUPON */
    private String linkType;

    /** 来源主键：课程 id / 秒杀活动 id / 券 id */
    private Long targetId;

    /** 课程名、秒杀课程名或券名 */
    private String sourceName;

    private Long courseId;

    private Long activityId;

    private Long couponId;

    private Integer sortNo;

    /** 1 上架 / 0 下架 */
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
