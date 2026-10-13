package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.VideoProgress;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 视频进度表 Mapper。
 */
public interface VideoProgressMapper extends BaseMapper<VideoProgress> {

    /**
     * 进度只增不减：new_progress > progress 才更新（CAS 思想防并发回退）。
     *
     * @return 影响行数：1 已更新；0 进度没有增长（无需处理）
     */
    @Update("UPDATE t_video_progress SET progress = #{newProgress}, last_report_at = NOW(), updated_at = NOW() " +
            "WHERE user_id = #{userId} AND video_id = #{videoId} AND progress < #{newProgress}")
    int advance(@Param("userId") Long userId,
                @Param("videoId") Long videoId,
                @Param("newProgress") Integer newProgress);

    /** 只刷新上报时间（进度没涨但时间在走：心跳/退出兜底场景） */
    @Update("UPDATE t_video_progress SET last_report_at = NOW(), updated_at = NOW() " +
            "WHERE user_id = #{userId} AND video_id = #{videoId}")
    int touchReport(@Param("userId") Long userId, @Param("videoId") Long videoId);
}
