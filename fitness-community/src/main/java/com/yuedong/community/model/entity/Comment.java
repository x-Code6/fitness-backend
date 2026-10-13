package com.yuedong.community.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论实体（PO），对应表 yd_community.t_comment。
 *
 * <p>两级评论结构：parent_id 为空是一级评论；回复某条评论时 parent_id 指向
 * 该评论、reply_to_user 记录被回复人昵称（前端展示"回复 @xxx"）。</p>
 */
@Data
@TableName("t_comment")
public class Comment {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属动态 id */
    @TableField("post_id")
    private Long postId;

    /** 评论人 id */
    @TableField("user_id")
    private Long userId;

    /** 评论人昵称快照（取自 user 服务，防改名） */
    @TableField("user_name")
    private String userName;

    /** 评论内容 */
    @TableField("content")
    private String content;

    /** 父评论 id（顶级评论为 0） */
    @TableField("parent_id")
    private Long parentId;

    /** 被回复人昵称（回复评论时写入） */
    @TableField("reply_to_user")
    private String replyToUser;

    /** 1 正常 / 0 已删除 */
    @TableField("status")
    private Integer status;

    /** 评论时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
