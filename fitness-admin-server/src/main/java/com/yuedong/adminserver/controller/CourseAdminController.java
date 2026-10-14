package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
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

    // ---------- 可售门店 ----------

    /** 某课程当前配置的可售门店 id 列表（表单回显） */
    @GetMapping("/store-list")
    public Result<List<Long>> storeList(@RequestParam Long courseId) {
        return courseClient.courseStoreList(courseId);
    }

    /**
     * 整体替换某课程的可售门店。
     *
     * <p>★v2 用这个接口取代原来的"场次管理"三个接口。旧模型是"给课程排时间"，
     * 新模型是"决定这门课在哪家店卖"——具体什么时候能上课，由教练时段决定，
     * 不在课程管理页配置。</p>
     */
    @OperateLog(module = "课程管理", operation = "配置可售门店")
    @PostMapping("/assign-store")
    public Result<Void> assignStore(@RequestParam Long courseId, @RequestBody List<Long> storeIds) {
        return courseClient.courseAssignStore(courseId, storeIds);
    }
}
