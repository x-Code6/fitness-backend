package com.yuedong.community.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 评论的一页（一级评论页 / 某个一级的回复页都用它）。
 *
 * <p>★和 {@link FeedResultVO} 分开：动态流要带 tabText/emptyText 这些"流"的文案，
 * 评论页不需要；评论页的游标是<b>升序</b>（越早越前，符合对话阅读顺序），
 * 动态流是倒序。两者放一个类里就会有一个字段在另一半场景里永远是 null。</p>
 */
@Data
@Builder
public class CommentPageVO {

    private List<CommentVO> list;

    /** 下一页游标（评论 id，升序）。没有更多时为 null */
    private String nextCursor;

    private boolean hasMore;
}
