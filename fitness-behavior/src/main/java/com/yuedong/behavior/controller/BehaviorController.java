package com.yuedong.behavior.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.behavior.model.dto.ReportDTO;
import com.yuedong.behavior.model.vo.HotCourseVO;
import com.yuedong.behavior.service.BehaviorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 埋点接口（report 允许匿名；hot 榜前端首页使用）。
 */
@RestController
@RequestMapping("/behavior")
@RequiredArgsConstructor
public class BehaviorController {

    private final BehaviorService behaviorService;

    /**
     * 批量埋点上报（前端 SDK 攒批调用）。
     *
     * <p>校验 → 立即发 Kafka，接口本身不落库，响应极快（上报路径不能拖慢页面）。</p>
     */
    @PostMapping("/report")
    public Result<Void> report(@Valid @RequestBody ReportDTO dto) {
        behaviorService.report(UserContext.getUserId(), dto);
        return Result.ok();
    }

    /**
     * 热门课程点击榜（首页"推荐课程"按此排序）。
     *
     * @param limit 取前 N，默认 3
     */
    @GetMapping("/hot/courses")
    public Result<List<HotCourseVO>> hot(@RequestParam(defaultValue = "3") int limit) {
        return Result.ok(behaviorService.hotCourses(limit));
    }
}
