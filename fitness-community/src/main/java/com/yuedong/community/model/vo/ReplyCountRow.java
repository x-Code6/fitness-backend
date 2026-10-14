package com.yuedong.community.model.vo;

import lombok.Data;

/**
 * 一级评论的回复条数（内部用，<b>不给前端</b>）。
 *
 * <p>★为什么不把回复数直接塞进 {@link CommentVO} 的一个字段再单查一次：
 * 一页 10 条一级评论，逐条 COUNT 就是 10 次查询（N+1）。
 * 这里配合 {@code CommentMapper#selectReplyCounts} 一条
 * {@code GROUP BY root_id} 全页拿完，再在内存里按 rootId 贴回 VO。</p>
 */
@Data
public class ReplyCountRow {

    /** 所属一级评论 id */
    private Long rootId;

    /** 该一级下未删除的回复条数 */
    private Integer replyCount;
}
