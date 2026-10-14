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

    /**
     * 可见性：PUBLIC 公开 / FRIEND 仅互关好友 / PRIVATE 私密。
     * <p>★存一列就够，但<b>查询策略必须按档分工</b>（docs 4.12 K3）：
     * 推荐流与关注流扇出只装 PUBLIC；FRIEND 用拉模式在"好友动态"里查；
     * 混进一个 OR 查询就会让索引失效。</p>
     */
    @TableField("visibility")
    private String visibility;

    /** 点赞数（冗余计数，只由本服务的原子 UPDATE 改） */
    @TableField("like_count")
    private Integer likeCount;

    /** 收藏数。★和点赞分列：语义不同（收藏=我要留着），热度加权也更高 */
    @TableField("collect_count")
    private Integer collectCount;

    /** 评论数（含二级回复） */
    @TableField("comment_count")
    private Integer commentCount;

    /**
     * 浏览量<b>账本</b>。
     * <p>★实时累加在 Redis Hash（{@code post:view:delta}），定时任务原子取出后
     * 用 {@code view_count = view_count + delta} 刷进这里。
     * DB 永远是真相、Redis 只是缓冲，所以 Redis 被清空只会"少一批未刷的增量"，
     * 而不是像旧实现那样把业务状态整个丢掉。</p>
     */
    @TableField("view_count")
    private Integer viewCount;

    /** 1 正常 / 0 已删除 */
    @TableField("status")
    private Integer status;

    /** 发布时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
