package com.yuedong.point.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水实体（PO），对应表 yd_point.t_points_log。
 *
 * <p>points 有正有负（+10 打卡 / -200 兑换）；余额 = 流水累加，
 * 但展示用 t_member_points.balance 冗余字段，避免每次累加。</p>
 */
@Data
@TableName("t_points_log")
public class PointsLog {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 id */
    @TableField("user_id")
    private Long userId;

    /** 流水标题，如"每日打卡"、"完成跟练：20 分钟全身燃脂 HIIT" */
    @TableField("title")
    private String title;

    /** 积分变动（正加负减） */
    @TableField("points")
    private Integer points;

    /** 业务类型：CHECKIN/COURSE_FINISHED/POST_PUBLISHED/EXCHANGE */
    @TableField("biz_type")
    private String bizType;

    /** 业务幂等 id（如完课事件的 eventId，防重复发分） */
    @TableField("biz_id")
    private String bizId;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
