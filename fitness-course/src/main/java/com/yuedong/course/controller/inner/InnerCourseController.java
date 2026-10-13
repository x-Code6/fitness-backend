package com.yuedong.course.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.ScheduleDTO;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import com.yuedong.course.service.CourseService;
import com.yuedong.course.service.SeckillActivityService;
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
 * 课程服务内部接口（/inner/** 只允许服务间经 Nacos 直连，网关显式拒绝外部访问）。
 * 签名与 fitness-course-api 的 CourseClient / CourseAdminClient 一一对应。
 */
@RestController
@RequestMapping("/inner/course")
@RequiredArgsConstructor
public class InnerCourseController {

    private final CourseService courseService;
    private final SeckillActivityService seckillActivityService;

    /** 课程简况（订单服务下单时查） */
    @GetMapping("/detail")
    public Result<CourseDTO> detail(@RequestParam Long id) {
        return Result.ok(courseService.getInnerDetail(id));
    }

    /** 场次简况（按场次预约时查上课时间） */
    @GetMapping("/schedule")
    public Result<ScheduleDTO> schedule(@RequestParam Long id) {
        return Result.ok(courseService.getScheduleInner(id));
    }

    /** 原子占用场次名额：false = 本场已满 */
    @PostMapping("/schedule/occupy")
    public Result<Boolean> occupySchedule(@RequestParam Long scheduleId) {
        return Result.ok(courseService.occupySchedule(scheduleId));
    }

    /** 原子释放场次名额 */
    @PostMapping("/schedule/release")
    public Result<Boolean> releaseSchedule(@RequestParam Long scheduleId) {
        return Result.ok(courseService.releaseSchedule(scheduleId));
    }

    /** 原子占用课程名额（无场次兼容路径）：false = 已满 */
    @PostMapping("/occupy")
    public Result<Boolean> occupy(@RequestParam Long courseId) {
        return Result.ok(courseService.occupy(courseId));
    }

    /** 原子释放课程名额（取消/超时未支付时回补） */
    @PostMapping("/release")
    public Result<Boolean> release(@RequestParam Long courseId) {
        return Result.ok(courseService.release(courseId));
    }

    /** 查询课程进行中的秒杀活动；无活动 data=null */
    @GetMapping("/seckill/active")
    public Result<SeckillActiveDTO> activeSeckill(@RequestParam Long courseId) {
        return Result.ok(seckillActivityService.activeSeckill(courseId));
    }

    /** 秒杀建单成功后扣减 DB 库存（Redis 已在 Lua 里先行扣减） */
    @PostMapping("/seckill/deduct")
    public Result<Boolean> deduct(@RequestParam Long activityId) {
        return Result.ok(seckillActivityService.deductRemain(activityId));
    }

    /** 手动预热指定活动库存（定时任务 30s 扫描兜底，此接口便于联调） */
    @PostMapping("/seckill/preheat")
    public Result<Boolean> preheat(@RequestParam Long activityId) {
        return Result.ok(seckillActivityService.preheat(activityId));
    }

    // =====================================================================
    // 管理端（fitness-admin-server Feign 调用）
    // =====================================================================

    /** 管理端：课程分页 */
    @GetMapping("/admin/page")
    public Result<PageDTO<CourseAdminDTO>> adminPage(@RequestParam(required = false) String type,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "1") Integer page,
                                                     @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(courseService.adminPage(type, keyword, page, size));
    }

    /** 管理端：创建/编辑课程 */
    @PostMapping("/admin/save")
    public Result<Void> adminSave(@Valid @RequestBody CourseSaveDTO dto) {
        courseService.adminSave(dto);
        return Result.ok();
    }

    /** 管理端：上架/下架 */
    @PostMapping("/admin/status")
    public Result<Void> adminStatus(@RequestParam Long id, @RequestParam Integer status) {
        courseService.adminStatus(id, status);
        return Result.ok();
    }

    /** 管理端：删除课程 */
    @PostMapping("/admin/delete")
    public Result<Void> adminDelete(@RequestParam Long id) {
        courseService.adminDelete(id);
        return Result.ok();
    }

    /** 管理端：课程的全部场次（含历史） */
    @GetMapping("/schedule/list")
    public Result<List<ScheduleDTO>> scheduleList(@RequestParam Long courseId) {
        return Result.ok(courseService.scheduleAdminList(courseId));
    }

    /** 管理端：新增场次 */
    @PostMapping("/schedule/save")
    public Result<Void> scheduleSave(@RequestParam Long courseId,
                                     @RequestParam String scheduleTime,
                                     @RequestParam Integer capacity) {
        courseService.scheduleAdminSave(courseId, scheduleTime, capacity);
        return Result.ok();
    }

    /** 管理端：删除场次 */
    @PostMapping("/schedule/delete")
    public Result<Void> scheduleDelete(@RequestParam Long scheduleId) {
        courseService.scheduleAdminDelete(scheduleId);
        return Result.ok();
    }
}
