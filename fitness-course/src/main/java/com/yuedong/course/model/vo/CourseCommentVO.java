package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 展示课评论的一行（一级评论带它的回复，两级）。
 *
 * <p>★字段名跟着前端已经写好的页面走（{@code commentId} 而不是 {@code id}、
 * {@code agoText} + {@code createdText} 两个都要）：
 * 这个页面已经完成、正在跑 Mock，BFF 之外的服务改字段名的成本比前端改一遍低。
 * {@code createdText} 是绝对时间、{@code agoText} 是相对时间，
 * 两个都给是因为列表要"刚刚"、点开详情要"2026-10-11 20:30"。</p>
 */
@Data
@Builder
public class CourseCommentVO {

    private Long commentId;

    private Long userId;

    private String userName;

    /** 头像渐变下标 */
    private Integer avatarColor;

    private String content;

    /** 绝对时间 yyyy-MM-dd HH:mm */
    private String createdText;

    /** 相对时间：刚刚 / X 分钟前 / 昨天 HH:mm / MM-dd */
    private String agoText;

    /** 被回复人昵称（一级为 null） */
    private String replyToUser;

    /** 是不是当前登录用户发的（决定要不要显示删除） */
    private boolean mine;

    /** 该一级评论下的回复，按时间升序（越早越前，符合对话阅读顺序） */
    private List<CourseCommentVO> replies;
}
