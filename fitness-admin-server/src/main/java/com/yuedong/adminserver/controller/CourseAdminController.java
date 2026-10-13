package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.ScheduleDTO;
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
 * 课程管理（含排期）：透传课程服务的内部管理接口。
 */
@RestController
@RequestMapping("/admin/course")
@RequiredArgsConstructor
public class CourseAdminController {

    private final CourseAdminClient courseClient;

    /** 课程分页 */
    @GetMapping("/page")
    public Result<PageDTO<CourseAdminDTO>> page(@RequestParam(required = false) String type,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "10") Integer size) {
        return courseClient.coursePage(type, keyword, page, size);
    }

    @OperateLog(module = "课程管理", operation = "保存课程")
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody CourseSaveDTO dto) {
        return courseClient.courseSave(dto);
    }

    @OperateLog(module = "课程管理", operation = "上下架课程")
    @PostMapping("/status")
    public Result<Void> status(@RequestParam Long id, @RequestParam Integer status) {
        return courseClient.courseStatus(id, status);
    }

    @OperateLog(module = "课程管理", operation = "删除课程")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam Long id) {
        return courseClient.courseDelete(id);
    }

    // ---------- 排期管理 ----------

    /** 某课程的场次列表 */
    @GetMapping("/schedule/list")
    public Result<List<ScheduleDTO>> scheduleList(@RequestParam Long courseId) {
        return courseClient.scheduleList(courseId);
    }

    @OperateLog(module = "排期管理", operation = "新增场次")
    @PostMapping("/schedule/save")
    public Result<Void> scheduleSave(@RequestParam Long courseId,
                                     @RequestParam String scheduleTime,
                                     @RequestParam Integer capacity) {
        return courseClient.scheduleSave(courseId, scheduleTime, capacity);
    }

    @OperateLog(module = "排期管理", operation = "删除场次")
    @PostMapping("/schedule/delete")
    public Result<Void> scheduleDelete(@RequestParam Long scheduleId) {
        return courseClient.scheduleDelete(scheduleId);
    }
}
