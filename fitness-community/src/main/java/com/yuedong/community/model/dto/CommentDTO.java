package com.yuedong.community.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论/回复请求参数。
 *
 * <p>回复某条评论时传 parentId（被回复的评论 id）；
 * 一级评论只传 postId + content。</p>
 */
@Data
public class CommentDTO {

    /** 动态 id */
    @NotNull(message = "动态 id 不能为空")
    private Long postId;

    /** 评论内容 */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 200, message = "评论最长 200 字")
    private String content;

    /** 回复的目标评论 id（一级评论不传） */
    private Long parentId;
}
