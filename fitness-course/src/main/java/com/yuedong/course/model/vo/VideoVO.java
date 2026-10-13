package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 视频视图对象（列表与详情共用）。
 */
@Data
@Builder
public class VideoVO {

    private Long id;

    private String title;

    private String coach;

    /** 时长（分钟） */
    private Integer duration;

    private String level;

    private Integer calories;

    /** 跟练人数 */
    private Integer plays;

    /** 当前用户的最大进度（列表页展示进度条；匿名/详情场景可为 null） */
    private Integer progress;
}
