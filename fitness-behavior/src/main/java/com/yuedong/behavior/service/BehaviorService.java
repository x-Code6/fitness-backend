package com.yuedong.behavior.service;

import com.yuedong.behavior.model.dto.ReportDTO;
import com.yuedong.behavior.model.vo.HotCourseVO;

import java.util.List;

/**
 * 行为埋点业务接口。
 */
public interface BehaviorService {

    /**
     * 接收批量埋点 → 逐条发 Kafka（key=deviceId 保证单设备事件有序）。
     * 接口本身不落库，落库由消费者完成——上报路径保持极快。
     */
    void report(Long userId, ReportDTO dto);

    /** 热门课程点击榜（当月，Redis ZSet TopN） */
    List<HotCourseVO> hotCourses(int limit);
}
