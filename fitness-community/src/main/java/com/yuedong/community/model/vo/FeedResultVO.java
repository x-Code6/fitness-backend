package com.yuedong.community.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Feed 流一页（<b>游标分页，不再有 page</b>，docs 4.12 K2）。
 *
 * <p>★换成游标的原因：offset 分页在"边翻边有人发新动态"时必然重复或漏 ——
 * 第 1 页取完 5 条，中间进来 1 条新的，第 2 页 {@code LIMIT 5 OFFSET 5} 会把第 6 条
 * （也就是原来第 5 条）再给你一遍。<b>推荐流尤其明显，因为它是算分的、顺序本身会变。</b></p>
 *
 * <p>{@code nextCursor} 对前端是<b>不透明字符串</b>：只要原样回传，不要解析、不要拼装。
 * 内部格式（时间流是 id、推荐流是 {@code score_id}）属于服务端实现细节，
 * 一旦前端开始依赖它，换分页策略就变成破坏性改动。</p>
 */
@Data
@Builder
public class FeedResultVO {

    private List<PostVO> list;

    /** 下一页游标；没有更多时为 null（前端据此收起"加载更多"，而不是靠 list 长度猜） */
    private String nextCursor;

    private boolean hasMore;

    /** 本流的标题文案（推荐/关注/好友/我的）。★后端给，避免前端四个 tab 四套说法 */
    private String tabText;

    /** 空流时的引导文案（未登录、没关注任何人、好友没发过动态，三种情况话不一样） */
    private String emptyText;
}
