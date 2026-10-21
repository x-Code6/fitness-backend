package com.yuedong.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.community.model.entity.Comment;
import com.yuedong.community.model.vo.ReplyCountRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 评论表 Mapper（两级评论：一级 root_id=0，回复 root_id=所属一级 id）。
 *
 * <p>★列表本身用 MyBatis-Plus 的条件构造器就够（都是等值 + id 游标），
 * 这里只留一条<b>必须写 SQL</b> 的：整页一级评论的回复数。</p>
 */
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 一次查回"这几个一级评论各有多少条回复"。
     *
     * <p>★为什么不逐条 COUNT：一页 10 条一级评论就是 10 次查询，
     * 前端 Feed 每翻一页都要付这个钱——这就是 N+1。
     * {@code GROUP BY root_id} 一次拿完整页，走 {@code idx_root_time(root_id, id)} 的
     * 最左前缀（root_id 是前导列，IN 里每个值做一次等值探查）。</p>
     *
     * <p>⚠️ 结果里<b>没有</b>"回复数为 0"的那些一级评论（GROUP BY 只返回有行的分组），
     * 贴回 VO 时要给默认值 0。</p>
     */
    @Select("<script>" +
            "SELECT root_id, COUNT(*) AS reply_count FROM t_post_comment" +
            " WHERE status = 1 AND root_id IN " +
            "<foreach collection='rootIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " GROUP BY root_id" +
            "</script>")
    List<ReplyCountRow> selectReplyCounts(@Param("rootIds") List<Long> rootIds);

    /**
     * 软删评论：只有"作者本人 + 当前还是正常状态"才更新成功。
     *
     * <p>★为什么不用 {@code updateById}：MySQL 把 status=0 再写成 0 也算影响 1 行，
     * 并发双击删除时两次都会返回 1，评论数就会被减两次。带 status=1 条件后，
     * 只有真正完成状态迁移的那一次返回 1。</p>
     *
     * @return 1 本次删除成功；0 已被删过或不是本人
     */
    @Update("UPDATE t_post_comment SET status = 0 WHERE id = #{commentId} " +
            "AND user_id = #{userId} AND status = 1")
    int softDelete(@Param("commentId") Long commentId, @Param("userId") Long userId);
}
