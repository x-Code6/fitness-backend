package com.yuedong.behavior.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行为日志实体（PO），对应表 yd_behavior.t_behavior_log。
 *
 * <p>这是 Kafka 消费端落库的"数仓明细层"：只写不改、按时间增长，
 * 后续所有行为分析（热门课程/漏斗/留存）都从这张表出发。</p>
 */
@Data
@TableName("t_behavior_log")
public class BehaviorLog {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 事件唯一 id（幂等键，前端 SDK 生成 UUID） */
    @TableField("event_id")
    private String eventId;

    /** 事件类型：page_view/course_click/seckill_click/checkin_click */
    @TableField("event_type")
    private String eventType;

    /** 事件目标（课程 id 等，无目标为 null） */
    @TableField("target_id")
    private Long targetId;

    /** 设备 id（匿名用户的身份；登录用户另有 user_id） */
    @TableField("device_id")
    private String deviceId;

    /** 用户 id（匿名时为 null） */
    @TableField("user_id")
    private Long userId;

    /** 客户端事件时间戳（毫秒） */
    @TableField("client_ts")
    private Long clientTs;

    /** 入库时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
