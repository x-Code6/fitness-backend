package com.yuedong.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.community.model.entity.Post;
import com.yuedong.community.model.vo.PostScoreRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 动态表 Mapper：计数一律<b>原子 UPDATE</b>，推荐流是<b>带游标的算分查询</b>。
 */
public interface PostMapper extends BaseMapper<Post> {

    /** 评论数 ±1（delta 传 +1 / -1）。★GREATEST 兜住"删除比计数多"的脏数据，不让它变负数 */
    @Update("UPDATE t_post SET comment_count = GREATEST(comment_count + #{delta}, 0) " +
            "WHERE id = #{postId}")
    int changeCommentCount(@Param("postId") Long postId, @Param("delta") int delta);

    /** 点赞数 ±1 */
    @Update("UPDATE t_post SET like_count = GREATEST(like_count + #{delta}, 0) " +
            "WHERE id = #{postId}")
    int changeLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    /** 收藏数 ±1 */
    @Update("UPDATE t_post SET collect_count = GREATEST(collect_count + #{delta}, 0) " +
            "WHERE id = #{postId}")
    int changeCollectCount(@Param("postId") Long postId, @Param("delta") int delta);

    /**
     * 浏览量<b>增量</b>入账（由定时任务调用）。
     * <p>★必须是 {@code view_count + delta}，不能 {@code = delta} 或 {@code = Redis 的值}：
     * 前者会把刷库间隙已入账的量覆盖掉，后者会被重复刷入。
     * delta 由 {@code HINCRBY -delta} 原子取出，所以正常情况下不会重也不会漏。</p>
     */
    @Update("UPDATE t_post SET view_count = view_count + #{delta} WHERE id = #{postId}")
    int addViewCount(@Param("postId") Long postId, @Param("delta") int delta);

    /**
     * 推荐流取一页（<b>keyset 游标 + 小时桶稳定</b>，docs 4.12 K2）。
     *
     * <p>公式：{@code score = LOG10(收藏×5 + 评论×3 + 点赞×2 + 浏览×0.2 + 1) / (距今小时数 + 2)^1.5}</p>
     * <ul>
     *   <li><b>权重按用户付出成本排</b>：点一下 &lt; 写一句 &lt; 收藏（收藏=我要留着，意图最强）；
     *       浏览最廉价也最易刷，所以只给 0.2；</li>
     *   <li><b>LOG10 压量级</b>：不然 1 万浏览和 10 万浏览会差 10 倍权重；</li>
     *   <li><b>+2 是新帖保护</b>（刚发的不至于分母为 0 就冲顶），<b>1.5 是 gravity</b>，越大沉得越快；</li>
     *   <li><b>时间用 TIMESTAMPDIFF(HOUR,...) 取整</b>，所以同一小时内分数完全不变 ——
     *       这就是"翻页不重复"的来处。用小数小时（更"实时"）就会漂，
     *       用户翻到第二页会看到上一页出现过的内容。</li>
     * </ul>
     *
     * <p>★为什么限定 {@code recentDays}：分数是算出来的、没有索引可用（MySQL 不能建表达式索引，
     * 至少这版不引）；不框时间窗就是<b>每次请求全表算分再排序</b>。
     * 一期只推荐近 7 天的公开动态，池子小、算得起，业务上也合理（老帖靠"最新"流和作者主页露出）。</p>
     *
     * @param lastScore  上一页最后一条的分数。★首页传 {@link #FIRST_PAGE_SCORE}
     * @param recentDays 参与推荐的天数窗口
     * @param limit      取几条。<b>★调用方传"页大小 + 1"</b>：
     *                   多取的那一条不返回给前端，只用来判断"还有没有下一页"——
     *                   这比再发一次 COUNT 便宜，也比"按长度猜"准（正好一整页时猜不出尽头）
     */
    @Select("SELECT t.post_id, t.score FROM (" +
            "  SELECT id AS post_id," +
            "         LOG10(collect_count * 5 + comment_count * 3 + like_count * 2" +
            "               + view_count * 0.2 + 1)" +
            "         / POW(TIMESTAMPDIFF(HOUR, created_at, NOW()) + 2, 1.5) AS score" +
            "    FROM t_post" +
            "   WHERE status = 1 AND visibility = 'PUBLIC'" +
            "     AND created_at >= DATE_SUB(NOW(), INTERVAL #{recentDays} DAY)" +
            ") t" +
            " WHERE t.score < #{lastScore}" +
            " ORDER BY t.score DESC, t.post_id DESC" +
            " LIMIT #{limit}")
    List<PostScoreRow> selectRecommendPage(@Param("lastScore") double lastScore,
                                           @Param("recentDays") int recentDays,
                                           @Param("limit") int limit);

    /**
     * 首页游标哨兵值。
     * <p>★用"比任何真实分数都大"而不是 {@code null}：SQL 里不用写
     * {@code #{lastScore} IS NULL OR score < ...} 这种分支，一条语句两种场景都能走。
     * 为什么不能取 1e308 那种极大值——{@code score < 1e308} 恒真，但 MySQL 会把 DECIMAL
     * 转 DOUBLE 时给边界值找麻烦；1e9 对"互动数上限十亿次"这个业务来说足够大了。</p>
     */
    double FIRST_PAGE_SCORE = 1_000_000_000d;
}
