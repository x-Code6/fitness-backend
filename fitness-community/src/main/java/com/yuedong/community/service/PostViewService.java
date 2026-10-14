package com.yuedong.community.service;

import java.util.Collection;
import java.util.Map;

/**
 * 浏览量服务（docs 4.12 K1 三层模型的应用侧入口）。
 *
 * <p>三层各有角色，任何一层塌了都能从下一层重建：</p>
 * <ul>
 *   <li>Kafka {@code yd-behavior-event} → {@code t_behavior_log}：原始明细，可重放；</li>
 *   <li>Redis Hash {@code post:view:delta}：只存<b>未入账的增量</b>；</li>
 *   <li>MySQL {@code t_post.view_count}：<b>账本（真相）</b>。</li>
 * </ul>
 */
public interface PostViewService {

    /**
     * 记一次浏览。<b>只对登录用户计数</b>：去重键是"人 + 帖 + 天"，
     * 匿名没有稳定身份，计进去等于给刷量开门。
     *
     * @return true=这一次真的加了一票；false=今天已经看过（被去重）或用户未登录
     */
    boolean countView(Long postId, Long userId);

    /** 某条动态未入账的增量（展示时叠加到账本上，"刚看完就 +1"的体感才是对的） */
    int currentDelta(Long postId);

    /** 整页增量：Feed 组装用一次 HMGET 拿完，★不在循环里逐条查 Redis */
    Map<Long, Integer> currentDeltas(Collection<Long> postIds);

    /**
     * 把 Redis 里的增量原子取出并入账 DB，由定时任务调用。
     *
     * @return 本轮入账的动态条数（增量为 0 的不算）
     */
    int flushToDb();
}
