package com.yuedong.community.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 点赞记录实体（PO），对应表 yd_community.t_post_like。
 * UNIQUE(post_id, user_id) 保证一人一赞。
 */
@Data
@TableName("t_post_like")
public class PostLike {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 动态 id */
    @TableField("post_id")
    private Long postId;

    /** 点赞用户 id */
    @TableField("user_id")
    private Long userId;

    /** 点赞时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
