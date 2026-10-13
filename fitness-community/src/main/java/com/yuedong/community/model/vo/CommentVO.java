package com.yuedong.community.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 评论视图对象（两级结构：一级评论携带 replies；字段与前端对齐）。
 */
@Data
@Builder
public class CommentVO {

    private Long id;

    private Long userId;

    private String userName;

    private String content;

    /** 回复目标昵称（展示"回复 @xxx"，一级评论为 null） */
    private String replyToUser;

    private String time;

    /** 该评论下的回复（仅一级评论有） */
    private List<CommentVO> replies;
}
