package com.yuedong.course.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表展示课评论/回复的请求参数。
 *
 * <p>★<b>没有 {@code replyToUser} 这个字段</b>（前端 Mock 里传了）。
 * 被回复人的昵称必须服务端从父评论那一行取：前端传来的值可以是任何字符串，
 * 一旦相信它，就能造出"回复 @阿泽教练：……"而那条根本不是我发的内容
 * ——在课程页上冒充教练说话，是社交内容里最容易出事的伪造点。</p>
 */
@Data
public class CourseCommentDTO {

    /** 评论哪门课 */
    @NotNull(message = "缺少课程 id")
    private Long courseId;

    /** 评论内容 */
    @NotBlank(message = "说点什么再发吧")
    @Size(max = 300, message = "评论最长 300 字")
    private String content;

    /** 回复哪条评论（一级评论不传） */
    private Long parentId;
}
