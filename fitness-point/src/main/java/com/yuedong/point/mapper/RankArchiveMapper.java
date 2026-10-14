package com.yuedong.point.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.point.model.entity.RankArchive;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 月榜归档 Mapper。
 */
public interface RankArchiveMapper extends BaseMapper<RankArchive> {

    /**
     * 批量写入归档行，同一 (season,user_id) 已存在则<b>覆盖</b>分数与名次。
     *
     * <p>★用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 而不是"先 DELETE 这个 season 再 INSERT"：
     * 删除与插入之间如果有查询请求，会看到"这个月还没有榜"的中间态；
     * upsert 全程都有数据，只是值在变。这也是<b>重跑安全</b>的那个理由——
     * 任务失败重来，第二次就是把同一批数据再覆盖一遍，不需要任何锁。</p>
     *
     * <p>★注解用 {@code @Insert} 而不是 {@code @Select}：这条语句没有结果集，
     * 用 @Select 会在运行时报"方法声明返回结果却拿不到 ResultSet"。</p>
     */
    @Insert("<script>INSERT INTO t_rank_archive" +
            " (season, user_id, score, rank_no, total_checkin, continuous_best) VALUES " +
            "<foreach collection='rows' item='r' separator=','>" +
            "(#{r.season}, #{r.userId}, #{r.score}, #{r.rankNo}, #{r.totalCheckin}, #{r.continuousBest})" +
            "</foreach>" +
            " ON DUPLICATE KEY UPDATE score = VALUES(score), rank_no = VALUES(rank_no)," +
            " total_checkin = VALUES(total_checkin), continuous_best = VALUES(continuous_best)," +
            " archived_at = NOW()</script>")
    int upsertBatch(@Param("rows") List<RankArchive> rows);

    /**
     * 某月榜单分页（历史榜读这一条，按归档时固化的 {@code rank_no} 升序）。
     *
     * <p>★用 MP 的 {@code Page} 参数而不是手写 LIMIT：分页插件会补一条 COUNT(*)
     * 并把 LIMIT 交给数据库，管理端与 H5 都能拿到 total。
     * 手写 LIMIT 在本项目里只用于"取前 N 条、不关心总数"的场景。</p>
     */
    @Select("SELECT * FROM t_rank_archive WHERE season = #{season} ORDER BY rank_no ASC")
    IPage<RankArchive> pageBySeason(Page<RankArchive> page, @Param("season") String season);
}
