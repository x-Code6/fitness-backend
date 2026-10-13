package com.yuedong.course.controller;

import com.yuedong.common.result.Result;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.course.model.vo.ScheduleVO;
import com.yuedong.course.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程接口。
 *
 * <p>网关路由：/api/course/** → 本服务 /course/**（list/detail/schedules 在免登录白名单内）。</p>
 */
@RestController
@RequestMapping("/course")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    /**
     * 课程列表：类型筛选 + 关键词搜索。
     *
     * @param type    课程类型，"全部"或空表示不过滤
     * @param keyword 匹配课程名/教练/标签
     */
    @GetMapping("/list")
    public Result<List<CourseVO>> list(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) String keyword) {
        return Result.ok(courseService.list(type, keyword));
    }

    /**
     * 课程详情（缓存三件套：空值缓存 / 逻辑过期 + 互斥重建 / 随机 TTL）。
     */
    @GetMapping("/detail")
    public Result<CourseVO> detail(@RequestParam Long id) {
        return Result.ok(courseService.getDetail(id));
    }

    /**
     * 某课程的未来可约场次列表（详情页"选择上课时间"）。
     */
    @GetMapping("/schedules")
    public Result<List<ScheduleVO>> schedules(@RequestParam Long courseId) {
        return Result.ok(courseService.scheduleList(courseId));
    }
}
