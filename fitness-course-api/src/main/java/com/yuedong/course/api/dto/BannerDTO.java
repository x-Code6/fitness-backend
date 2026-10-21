package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户端首页活动轮播项。
 * <p>前端只按 linkType + 对应 id 跳转，后端不拼前端路由。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BannerDTO {

    private Long id;

    private String title;

    private String subtitle;

    /** 相对访问路径，如 /uploads/banner/xxx.jpg；前端加 /api 前缀 */
    private String imageUrl;

    /** COURSE / SECKILL / COUPON */
    private String linkType;

    private Long courseId;

    private Long activityId;

    private Long couponId;

    private Integer sortNo;
}
