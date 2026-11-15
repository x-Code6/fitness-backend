package com.yuedong.course.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CoachAdminDTO;
import com.yuedong.course.api.dto.CoachDTO;
import com.yuedong.course.api.dto.CoachSaveDTO;
import com.yuedong.course.model.entity.Coach;
import com.yuedong.course.service.CoachService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教练内部接口（仅供 fitness-admin-server 经 Feign 调用，不对外）。
 *
 * <p>为什么放在 {@code /inner/**} 前缀下：网关的 {@code AuthGlobalFilter} 第 1 步就会
 * 硬拒任何含 {@code /inner/} 的外部请求，服务之间则经 Nacos 直连绕过网关。
 * 于是"内部接口"这件事是由<b>路径约定 + 网关拦截</b>共同保证的，
 * 新增内部接口时忘记挂到 /inner 下 = 直接对外暴露，这是要盯的一条红线。</p>
 */
@RestController
@RequestMapping("/inner/coach")
@RequiredArgsConstructor
@Validated   // 让方法参数上的 @NotNull/@Min 这类约束生效（@Valid 只管 @RequestBody）
public class InnerCoachController {

    private final CoachService coachService;

    /** 管理端：某门店的教练列表（含停用与联系电话，返回实体不返回 CoachVO） */
    @GetMapping("/list")
    public Result<List<Coach>> listByStore(@RequestParam Long storeId) {
        return Result.ok(coachService.listByStore(storeId));
    }

    /**
     * 内部：这门课在这家店的可约教练（order 服务算时段用）。
     * <p>只回可预约需要的字段：工时区间 + 可约星期，<b>不回 phone</b>。</p>
     */
    @GetMapping("/bookable")
    public Result<List<CoachDTO>> bookable(@RequestParam Long courseId, @RequestParam Long storeId) {
        List<CoachDTO> rows = new java.util.ArrayList<>();
        for (Coach coach : coachService.listBookable(courseId, storeId)) {
            rows.add(CoachDTO.builder()
                    .coachId(coach.getId())
                    .name(coach.getName())
                    .title(coach.getTitle())
                    .avatarColor(coach.getAvatarColor())
                    .storeId(coach.getStoreId())
                    .workStartHour(coach.getWorkStartHour())
                    .workEndHour(coach.getWorkEndHour())
                    .workDays(coach.getWorkDays())
                    .build());
        }
        return Result.ok(rows);
    }

    /** 管理端：新增或修改教练，返回教练 id（新增后前端要用它去配课） */
    @PostMapping("/save")
    public Result<Long> save(@RequestBody @Valid CoachSaveDTO dto) {
        return Result.ok(coachService.save(dto));
    }

    /** 管理端：查某教练已配的课程 id，用于表单回显 */
    @GetMapping("/course-ids")
    public Result<List<Long>> courseIds(@RequestParam Long coachId) {
        return Result.ok(coachService.courseIdsOf(coachId));
    }

    /** 管理端：教练分页（四个筛选项都允许为 null，null 表示不按该项过滤） */
    @GetMapping("/admin/page")
    public Result<PageDTO<CoachAdminDTO>> adminPage(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer gender,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(coachService.adminPage(storeId, status, gender, keyword, page, size));
    }

    /** 管理端：启用/停用教练 */
    @PostMapping("/admin/status")
    public Result<Void> adminStatus(@RequestParam Long id, @RequestParam Integer status) {
        coachService.adminStatus(id, status);
        return Result.ok();
    }

    /**
     * 管理端：删除教练。
     * <p>★调用前 admin-server 必须已经确认"该教练没有未完成的预约"——
     * 那张表在 yd_order，本服务查不到，所以这个前置条件写在契约上而不是写在代码里，
     * 改这个接口的人要一起改 admin-server 那侧的编排。</p>
     */
    @PostMapping("/admin/delete")
    public Result<Void> adminDelete(@RequestParam Long id) {
        coachService.adminDelete(id);
        return Result.ok();
    }

    /**
     * 管理端：整体替换某教练的可授课范围。
     *
     * <p>传空数组即清空配课，因此 {@code courseIds} 允许为空集合，
     * 但<b>不允许为 null</b>——null 说明调用方漏传参数，和"我要清空"是两件事，
     * 混在一起会做出"本想保存却把配课清了"的事故。</p>
     */
    @PostMapping("/assign-course")
    public Result<Void> assignCourse(@RequestParam Long coachId, @RequestBody List<Long> courseIds) {
        if (courseIds == null) {
            return Result.fail(ErrorCode.PARAM_ERROR, "courseIds 不能为 null，清空请传空数组");
        }
        coachService.assignCourses(coachId, courseIds);
        return Result.ok();
    }
}
