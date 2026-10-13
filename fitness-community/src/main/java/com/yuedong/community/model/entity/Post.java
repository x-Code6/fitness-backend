package com.yuedong.community.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态实体（PO），对应表 yd_community.t_post。
 *
 * <p>作者昵称/头像色是"发布时快照"：读 Feed 时不需要回查用户服务，
 * 用户改昵称后旧动态保持历史昵称（社交产品惯例）。</p>
 */
@Data
@TableName("t_post")
public class Post {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 作者 id */
    @TableField("user_id")
    private Long userId;

    /** 作者昵称快照（发动态时取自 user 服务） */
    @TableField("user_name")
    private String userName;

    /** 作者头像配色快照 */
    @TableField("avatar_color")
    private String avatarColor;

    /** 动态正文（上限 300 字） */
    @TableField("content")
    private String content;

    /** 配图（渐变色下标 csv；真实项目为图片 url 列表） */
    @TableField("images")
    private String images;

    /** 话题（如"打卡日常"，单选） */
    @TableField("topic")
    private String topic;

    /** 点赞数（冗余计数，点赞/取消时增减） */
    @TableField("like_count")
    private Integer likeCount;

    /** 评论数（冗余计数） */
    @TableField("comment_count")
    private Integer commentCount;

    /** 1 正常 / 0 已删除 */
    @TableField("status")
    private Integer status;

    /** 发布时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
