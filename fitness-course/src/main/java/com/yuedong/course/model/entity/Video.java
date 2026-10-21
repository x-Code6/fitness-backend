package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 跟练视频实体（PO），对应表 yd_course.t_video。
 *
 * <p>★字段必须和 {@code sql/02_yd_course.sql} 的 DDL 逐列对齐。这里曾经映射过一个
 * 不存在的 {@code coach} 列（v2 建表时改成了 {@code coach_id} 外键），后果不是"少个字段"
 * 而是 {@code selectList} 直接 {@code Unknown column 'coach in field list'} ——
 * 整个视频列表接口 500。同库能 JOIN 的名字就不冗余存，取的时候一次批量 JOIN。</p>
 */
@Data
@TableName("t_video")
public class Video {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 属于哪门展示课程（→ t_course.id）；NULL = 首页跟练专区的独立视频 */
    @TableField("course_id")
    private Long courseId;

    /** 出镜教练（→ t_coach.id）。★教练名不冗余在这里，见类注释 */
    @TableField("coach_id")
    private Long coachId;

    /** 视频标题 */
    @TableField("title")
    private String title;

    /** 简介 */
    @TableField("intro")
    private String intro;

    /** 难度：入门/中级/进阶 */
    @TableField("level")
    private String level;

    /** 时长（分钟）。★进度防刷要按它换算，不能缺 */
    @TableField("duration")
    private Integer duration;

    /** 预计消耗千卡 */
    @TableField("calories")
    private Integer calories;

    /** 跟练人数（计数列，只增不减，非精确统计） */
    @TableField("plays")
    private Integer plays;

    /** ★播放地址，真实项目为 OSS/CDN 外链。VO 必须透出，否则前端播放器只能是假的 */
    @TableField("url")
    private String url;

    /** 封面相对路径或外链 */
    @TableField("cover_image")
    private String coverImage;

    /** 课程内排序号 */
    @TableField("sort_no")
    private Integer sortNo;

    /** 1 上架 / 0 下架 */
    @TableField("status")
    private Integer status;
}
