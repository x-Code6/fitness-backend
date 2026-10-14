package com.yuedong.community.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论实体（PO），对应表 yd_community.t_post_comment。
 *
 * <p>★表名带 post_ 前缀，和 t_post_like / t_post_collect 成一族：
 * 社区里"挂在动态下面的东西"三个前缀一致，将来加展示课评论
 * （t_course_comment，那是另一套准入与统计口径）时不会混。
 * 旧实体写的 t_comment 与建表脚本不同源，属于"启动不报错、一查报表不存在"。</p>
 *
 * <p>两级评论：一级评论 root_id=0、parent_id 为 NULL；
 * 回复 root_id=所属一级 id、parent_id=被直接回复那条评论（只用来渲染"回复 @某人"，不参与分组）。</p>
 */
@Data
@TableName("t_post_comment")
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

    /**
     * 直接回复的评论 id；<b>一级评论为 null</b>。
     * <p>★只用于渲染"回复 @某人"这一行，不参与分组（旧注释写"顶级评论为 0"和代码里的
     * {@code getParentId() == null} 判断互相矛盾，本次统一成 null）。</p>
     */
    @TableField("parent_id")
    private Long parentId;

    /**
     * 所属一级评论 id；<b>自己就是一级时为 0</b>。
     * <p>★这一列是"两级评论"能便宜下来的关键：没有它，"展开某个一级评论下的 100 条回复"
     * 要么递归查询、要么按 parent_id 一层层捞；有它就是一条 {@code root_id=?} 的平铺查询。
     * 仍然不做无限树——递归难写难测，手机上缩进超过两层根本不可读。</p>
     */
    @TableField("root_id")
    private Long rootId;

    /** 头像配色快照（评论要画头像。★不匿名：点评论人应该能进他的主页） */
    @TableField("avatar_color")
    private String avatarColor;

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
