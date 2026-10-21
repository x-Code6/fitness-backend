package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.VideoProgress;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 视频进度表 Mapper。
 */
public interface VideoProgressMapper extends BaseMapper<VideoProgress> {

    /**
     * 进度只增不减：new_progress &gt; progress 才更新（CAS 思想防并发回退）。
     *
     * <p>★P0-3 的修法就在 {@code finished = IF(...)} 这一句上。以前这条 UPDATE 从不碰
     * {@code finished} 列，于是它<b>永远是 0</b>（除了"第一次上报就直接 100%"那条 INSERT 路径）。
     * 后果不是显示问题而是<b>钱</b>：{@code VideoServiceImpl} 靠读 {@code finished}
     * 判断"是不是第一次完课"，读到永远 false 就会每次上报都发一次完课事件 → +30 分反复到账。</p>
     *
     * <p>★为什么写 {@code IF(#{newProgress} >= 100, 1, finished)} 而不是
     * {@code IF(progress >= 100, ...)}：MySQL 的 SET 是<b>从左到右</b>求值的，
     * 上面那句已经把 progress 改成了新值，这里再引用 progress 拿到的是新值——
     * 逻辑上恰好也对，但<b>读代码的人必须知道这条隐式规则才会放心</b>。
     * 直接用参数就把这个依赖消掉了：条件只看我传进来的那个数，和列的更新顺序无关。
     *（同一套道理见 {@code EntitlementMapper#consumeOne} 里 SET 子句的先后顺序。）</p>
     *
     * <p>★这里<b>不写</b> {@code finished = 1}：进度 90% 的上报也会走到这条语句，
     * 无条件置 1 就变成"看一点就算完课"。</p>
     *
     * @return 影响行数：1 已更新；0 进度没有增长（无需处理）
     */
    @Update("UPDATE t_video_progress SET progress = #{newProgress}, " +
            "finished = IF(#{newProgress} >= 100, 1, finished), " +
            "last_report_at = NOW(), updated_at = NOW() " +
            "WHERE user_id = #{userId} AND video_id = #{videoId} AND progress < #{newProgress}")
    int advance(@Param("userId") Long userId,
                @Param("videoId") Long videoId,
                @Param("newProgress") Integer newProgress);

    /** 只刷新上报时间（进度没涨但时间在走：心跳/退出兜底场景） */
    @Update("UPDATE t_video_progress SET last_report_at = NOW(), updated_at = NOW() " +
            "WHERE user_id = #{userId} AND video_id = #{videoId}")
    int touchReport(@Param("userId") Long userId, @Param("videoId") Long videoId);

    /** 标记完课事件已发送：只有发送成功才把 0 改成 1，失败时留在 0 等补偿任务 */
    @Update("UPDATE t_video_progress SET finish_event_sent = 1, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND video_id = #{videoId}")
    int markFinishEventSent(@Param("userId") Long userId, @Param("videoId") Long videoId);

    /**
     * 查“已完课但事件还没发出去”的记录，供补偿任务重发。
     * <p>按 updated_at 升序，先处理等得最久的；任务每轮最多取 20 条，避免一次拖太多。</p>
     */
    @Select("SELECT * FROM t_video_progress " +
            "WHERE finished = 1 AND finish_event_sent = 0 " +
            "ORDER BY updated_at ASC LIMIT #{limit}")
    List<VideoProgress> selectUnsentFinished(@Param("limit") int limit);
}
