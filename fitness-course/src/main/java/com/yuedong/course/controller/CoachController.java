package com.yuedong.course.controller;

import com.yuedong.common.result.Result;
import com.yuedong.course.model.vo.CoachVO;
import com.yuedong.course.service.CoachService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教练查询接口（C 端）。
 *
 * <p>网关路由：{@code /api/course/**} → 本服务（StripPrefix=1），
 * 所以对外完整路径是 {@code /api/course/coach/list}。
 * 挂在 {@code /course} 前缀下是刻意的：教练是课程域资源，
 * 不该像 {@code /api/coach/**} 那样单独占一个网关前缀（那需要再改网关路由，收益不大）。</p>
 */
@RestController
@RequestMapping("/course/coach")
@RequiredArgsConstructor
public class CoachController {

    private final CoachService coachService;

    /**
     * 查"这门课在这家店有哪些教练可约"。
     *
     * <p>时段页的第一步：先按课程 + 门店把候选教练列出来，
     * 再由 order 域的时段接口给出每个教练的空闲格子（见 docs 4.3.3 的 SlotPageVO）。</p>
     *
     * <p>需要登录：本接口不在网关白名单里。理由不是"教练信息保密"，而是
     * 时段页整体属于已购用户的预约流程，游客点进来也约不了，
     * 放开了只会多一个能被刷的匿名接口。</p>
     *
     * @param courseId 课程 id
     * @param storeId  门店 id
     */
    @GetMapping("/list")
    public Result<List<CoachVO>> list(@RequestParam Long courseId, @RequestParam Long storeId) {
        return Result.ok(coachService.listByCourseAndStore(courseId, storeId));
    }
}
