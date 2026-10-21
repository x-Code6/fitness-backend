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

    /**
     * 完课事件是否已经成功发送。
     * <p>★0 表示“进度已完成，但积分事件没发出去”，由定时任务补偿重发；
     * 消费端按 {@code userId:videoId} 幂等，重发不会重复加积分。</p>
     */
    @TableField("finish_event_sent")
    private Boolean finishEventSent;

    /**
     * 首次上报时间。
     * <p>★防刷要按"从第一次上报到现在总共过了多久"算允许的进度，
     * 只看 last_report_at 会被“每 10 秒报一次小增量”绕过。</p>
     */
    @TableField("first_report_at")
    private LocalDateTime firstReportAt;

    /** 上次上报时间（防刷速率校验：进度增量不得超过真实耗时过多） */
    @TableField("last_report_at")
    private LocalDateTime lastReportAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
