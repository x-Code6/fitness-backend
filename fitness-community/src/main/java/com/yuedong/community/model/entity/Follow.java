package com.yuedong.community.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注关系实体（PO），对应表 yd_community.t_follow。
 *
 * <p>DB 是权威数据；Redis Set（community:following:{userId}）是"共同关注 SINTER"
 * 与 Feed 推模式收件箱的加速结构，两者在关注/取关时同步维护。</p>
 */
@Data
@TableName("t_follow")
public class Follow {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关注人 */
    @TableField("user_id")
    private Long userId;

    /** 被关注人 */
    @TableField("followee_id")
    private Long followeeId;

    /** 关注时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
