package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.CoachAdminDTO;
import com.yuedong.course.api.dto.CoachSaveDTO;
import com.yuedong.order.api.client.BookingClient;
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
 * 教练管理（v2 新增的管理域）。
 *
 * <p>★这条链路的形状值得说一句：教练的<b>身份</b>在 course 服务，
 * 而"他还有没有未完成的预约"这件事在 order 服务。删除动作要同时满足两边，
 * 于是由 BFF 先问 order、再让 course 删 ——
 * <b>跨库的前置校验交给 BFF 编排，绝不让两个业务服务互相依赖</b>。
 * 一旦 course 依赖 order 的接口，服务依赖就成环了，注册发现、启动顺序、故障传播全跟着复杂。</p>
 */
@RestController
@RequestMapping("/admin/coach")
@RequiredArgsConstructor
public class CoachAdminController {

    private final CourseAdminClient courseClient;
    private final BookingClient orderClient;

    /** 教练分页：门店/状态/性别/关键词四个筛选位都可以不传 */
    @GetMapping("/page")
    public Result<PageDTO<CoachAdminDTO>> page(@RequestParam(required = false) Long storeId,
                                               @RequestParam(required = false) Integer status,
                                               @RequestParam(required = false) Integer gender,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "1") Integer page,
                                               @RequestParam(defaultValue = "10") Integer size) {
        return courseClient.coachPage(storeId, status, gender, keyword, page, size);
    }

    /**
     * 新增/编辑教练。
     * <p>★返回教练 id 而不是 void：新建之后前端要立刻拿它去配课，
     * 少了这个返回值就得再查一次列表去找"刚建的那位"。</p>
     */
    @OperateLog(module = "教练管理", operation = "保存教练")
    @PostMapping("/save")
    public Result<Long> save(@Valid @RequestBody CoachSaveDTO dto) {
        return courseClient.coachSave(dto);
    }

    /** 启用/停用（停用后不再出现在可约教练里，历史预约单不受影响） */
    @OperateLog(module = "教练管理", operation = "启停教练")
    @PostMapping("/status")
    public Result<Void> status(@RequestParam Long id, @RequestParam Integer status) {
        return courseClient.coachStatus(id, status);
    }

    /**
     * 删除教练。
     * <p>★先问 order"他还有未开始的预约吗"，有就直接拒掉：
     * 预约单上的教练名是快照，删了不会让历史记录出错，
     * 但<b>用户界面里那节待上的课会指向一个不存在的教练</b>，用户到店找不到人才知道坏了。
     * 这种"技术上能删、业务上不该删"的判断，正是 BFF 该做的事。</p>
     */
    @OperateLog(module = "教练管理", operation = "删除教练")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam Long id) {
        Boolean busy = orderClient.coachHasFuture(id).getData();
        if (Boolean.TRUE.equals(busy)) {
            return Result.fail(com.yuedong.common.result.ErrorCode.PARAM_ERROR,
                    "该教练还有未开始的预约，请先处理这些时段再删除");
        }
        return courseClient.coachDelete(id);
    }

    /** 某教练已配课程 id（表单回显） */
    @GetMapping("/course-ids")
    public Result<List<Long>> courseIds(@RequestParam Long coachId) {
        return courseClient.coachCourseIds(coachId);
    }

    /** 配课：整组覆盖（body 传课程 id 数组，空数组=清空） */
    @OperateLog(module = "教练管理", operation = "配置教练课程")
    @PostMapping("/assign-course")
    public Result<Void> assignCourse(@RequestParam Long coachId, @RequestBody List<Long> courseIds) {
        return courseClient.coachAssignCourse(coachId, courseIds);
    }
}
