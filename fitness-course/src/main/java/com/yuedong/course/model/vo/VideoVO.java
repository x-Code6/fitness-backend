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

    /** 简介 */
    private String intro;

    /** 出镜教练名。★由服务层批量 JOIN t_coach 得到，实体里只有 coachId */
    private String coach;

    private Long coachId;

    /** 所属展示课程；NULL = 跟练专区的独立视频 */
    private Long courseId;

    /** 时长（分钟） */
    private Integer duration;

    private String level;

    private Integer calories;

    /** 跟练人数 */
    private Integer plays;

    /** ★播放地址（OSS/CDN 外链）。以前没透出，前端播放器只能是假的 */
    private String url;

    /** 封面 */
    private String coverImage;

    /** 当前用户的最大进度（列表页展示进度条；匿名/详情场景可为 null） */
    private Integer progress;
}
