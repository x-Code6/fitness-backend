package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 跟练视频实体（PO），对应表 yd_course.t_video。
 */
@Data
@TableName("t_video")
public class Video {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 视频标题 */
    @TableField("title")
    private String title;

    /** 教练名 */
    @TableField("coach")
    private String coach;

    /** 时长（分钟） */
    @TableField("duration")
    private Integer duration;

    /** 难度：入门/中级/进阶 */
    @TableField("level")
    private String level;

    /** 消耗（千卡） */
    @TableField("calories")
    private Integer calories;

    /** 跟练人数（展示用文案数字，如 12000） */
    @TableField("plays")
    private Integer plays;

    /** 视频地址（真实项目为 OSS/CDN 地址，Mock 阶段用模拟播放器） */
    @TableField("url")
    private String url;
}
