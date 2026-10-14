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
 * <p><b>DB 是唯一数据源</b>。旧实现额外往 Redis Set {@code community:following:{userId}}
 * 写一份"我关注的人"给 SINTER 用，而那份集合没有任何重建入口——清一次 Redis，
 * 共同关注就永久返回空，关注列表却照常（它读 DB）。缓存与真相同源之后，
 * 这条集合已整个删除，共同关注改成 DB 求交集。</p>
 *
 * <p>"好友"= 互关，靠一条自连接 SQL 现算，<b>不落 t_friend 表</b>（见 FollowMapper 注释）。</p>
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
