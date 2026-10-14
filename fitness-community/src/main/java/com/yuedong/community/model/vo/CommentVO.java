package com.yuedong.community.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 评论视图对象（两级：一级评论携带前几条回复）。
 *
 * <p>★结构上的取舍：<b>一级分页返回，回复只带前 N 条 + 总数</b>。
 * 一次把一级和它的所有回复全捞出来，在"爆款评论有 200 条回复"这种单子上会一次拖出几百行；
 * 但只返回一级、回复全部另开接口，前端又要发两次请求才能画出最常见的"前两条回复"。
 * 所以默认带前几条、展开时再按 {@code rootId} 游标翻页。</p>
 */
@Data
@Builder
public class CommentVO {

    private Long id;

    private Long userId;

    private String userName;

    /** 头像配色下标 */
    private Integer avatarIdx;

    private String content;

    /** 回复目标昵称（展示"回复 @xxx"，一级评论为 null） */
    private String replyToUser;

    /** 所属一级评论 id；自己是一级时为 0 */
    private Long rootId;

    private String time;

    /** 该一级评论下的回复总数（含被折叠未返回的） */
    private Integer replyCount;

    /** 是否还有更多回复没返回（前端据此显示"查看全部 N 条回复"） */
    private Boolean hasMoreReplies;

    /** 该评论下的回复（仅一级评论有；★按 id 升序，越早越前，符合对话阅读顺序） */
    private List<CommentVO> replies;

    /** 是不是当前用户发的（决定右上角要不要显示删除） */
    private Boolean mine;

    /**
     * 已被删除（占位显示）。
     * <p>★软删的评论<b>不从列表里抹掉而是留个占位</b>：一级评论被删时它下面还挂着别人的回复，
     * 直接过滤会让那串回复无处可挂（前端表现为"回复凭空少了几条"，作者会以为丢了数据）。
     * 动态列表可以过滤掉已删的，那里没有父子结构。</p>
     */
    private Boolean deleted;
}
