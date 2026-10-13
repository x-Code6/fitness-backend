package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频学习进度实体（PO），对应表 yd_course.t_video_progress。
 *
 * <p>UNIQUE(user_id, video_id)：一人一视频一条记录，进度只增不减。</p>
 */
@Data
@TableName("t_video_progress")
public class VideoProgress {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 id */
    @TableField("user_id")
    private Long userId;

    /** 视频 id */
    @TableField("video_id")
    private Long videoId;

    /** 最大播放进度 0~100 */
    @TableField("progress")
    private Integer progress;

    /** 是否已完课（首次达到 100 时发一次完课事件） */
    @TableField("finished")
    private Boolean finished;

    /** 上次上报时间（防刷速率校验：进度增量不得超过真实耗时过多） */
    @TableField("last_report_at")
    private LocalDateTime lastReportAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
