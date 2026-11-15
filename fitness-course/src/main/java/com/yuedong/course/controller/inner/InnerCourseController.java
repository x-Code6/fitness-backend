package com.yuedong.course.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.AdminCountDTO;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.CourseStoreKeyDTO;
import com.yuedong.course.api.dto.CourseWindowAdminDTO;
import com.yuedong.course.api.dto.CourseWindowSaveDTO;
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
 * 课程服务内部接口。
 *
 * <p>{@code /inner/**} 只允许服务间经 Nacos 直连：网关的 {@code AuthGlobalFilter}
 * 第 1 步就硬拒任何含 {@code /inner/} 的外部请求。所以新增内部接口时
 * <b>必须挂在这个前缀下</b>，忘了就等于直接对外暴露。</p>
 *
 * <p>接口签名与 {@code fitness-course-api} 的 CourseClient / CourseAdminClient 一一对应，
 * 两边必须同时改，这也是把契约抽到 api 模块的价值：改错了编译期就报。</p>
 *
 * <p>★v2 删掉的方法：{@code /schedule}、{@code /schedule/occupy}、{@code /schedule/release}、
 * {@code /occupy}、{@code /release}。它们是"跨服务占座位"的产物——购买不再占座位，
 * 名额判断变成预约时的时段占用。旧 {@code BookingServiceImpl} 里为这几处调用配的
 * 三段失败补偿代码也一并消失：<b>删掉的不是功能，是 bug 的滋生地</b>。</p>
 */
@RestController
@RequestMapping("/inner/course")
@RequiredArgsConstructor
public class InnerCourseController {

    private final CourseService courseService;
    private final SeckillActivityService seckillActivityService;

    /**
     * 课程简况（order 服务建单时查）。
     *
     * @param courseId 课程 id
     * @param storeId  购买门店。★必传：门店名快照与"是否在可售窗口内"都由
     *                 {@code t_course_store} 决定，没有门店上下文这两件事无从判断
     */
    @GetMapping("/detail")
    public Result<CourseDTO> detail(@RequestParam Long courseId, @RequestParam Long storeId) {
        return Result.ok(courseService.getInnerDetail(courseId, storeId));
    }

    /**
     * 批量课程快照（order 的购物车用）。
     * <p>★传的是"课 × 店"的<b>对</b>而不是两个列表：同一门课在不同店的价格窗口与可售状态不同，
     * 拆成两个集合就得在服务端做笛卡尔积再筛，语义会丢。</p>
     */
    @PostMapping("/batch-detail")
    public Result<List<CourseDTO>> batchDetail(@RequestBody List<CourseStoreKeyDTO> keys) {
        return Result.ok(courseService.getBatchDetail(keys));
    }

    /** 查课程进行中的秒杀活动；无活动时 data=null */
    @GetMapping("/seckill/active")
    public Result<SeckillActiveDTO> activeSeckill(@RequestParam Long courseId) {
        return Result.ok(seckillActivityService.activeSeckill(courseId));
    }

    /** 秒杀建单成功后扣减 DB 侧份数（Redis 已在 Lua 里先行扣减） */
    @PostMapping("/seckill/deduct")
    public Result<Boolean> deduct(@RequestParam Long activityId) {
        return Result.ok(seckillActivityService.deductRemain(activityId));
    }

    /**
     * 订单取消/超时/建单失败时回补 DB 侧份数。
     * <p>★Redis 是门神、DB 是账本；只回补 Redis 会让 DB 名额永久少卖。</p>
     */
    @PostMapping("/seckill/restore")
    public Result<Boolean> restore(@RequestParam Long activityId) {
        return Result.ok(seckillActivityService.restoreRemain(activityId));
    }

    /** 手动预热指定活动库存（定时任务 30 秒扫描兜底，此接口便于联调） */
    @PostMapping("/seckill/preheat")
    public Result<Boolean> preheat(@RequestParam Long activityId) {
        return Result.ok(seckillActivityService.preheat(activityId));
    }

    // =====================================================================
    // 管理端（fitness-admin-server 经 Feign 调用）
    // =====================================================================

    /** 管理端：课程分页（类型/课种/上下架/关键词四个筛选位，不传即不过滤） */
    @GetMapping("/admin/page")
    public Result<PageDTO<CourseAdminDTO>> adminPage(@RequestParam(required = false) String type,
                                                     @RequestParam(required = false) String kind,
                                                     @RequestParam(required = false) Integer status,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "1") Integer page,
                                                     @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(courseService.adminPage(type, kind, status, keyword, page, size));
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

    /** 管理端：某课程的可售门店（原"场次列表"位置换成门店列表） */
    @GetMapping("/admin/store-list")
    public Result<List<Long>> adminStoreList(@RequestParam Long courseId) {
        return Result.ok(courseService.adminStoreIds(courseId));
    }

    /** 管理端：整体替换某课程的可售门店（先删后插，与教练配课同一套语义） */
    @PostMapping("/admin/assign-store")
    public Result<Void> adminAssignStore(@RequestParam Long courseId, @RequestBody List<Long> storeIds) {
        if (storeIds == null) {
            return Result.fail(ErrorCode.PARAM_ERROR,
                    "storeIds 不能为 null，清空请传空数组");
        }
        courseService.adminAssignStores(courseId, storeIds);
        return Result.ok();
    }

    /** 管理端：课程 × 门店两层时间窗分页 */
    @GetMapping("/admin/window/page")
    public Result<PageDTO<CourseWindowAdminDTO>> adminWindowPage(
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) Long storeId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(courseService.adminWindowPage(courseId, storeId, page, size));
    }

    /** 管理端：保存某门课在某家店的售卖窗口与凭证有效期 */
    @PostMapping("/admin/window/save")
    public Result<Void> adminWindowSave(@Valid @RequestBody CourseWindowSaveDTO dto) {
        courseService.adminWindowSave(dto);
        return Result.ok();
    }

    /** 管理端看板：课程侧计数（在售课/可约教练/营业门店/今日应开放格子） */
    @GetMapping("/admin/count")
    public Result<AdminCountDTO> adminCount() {
        return Result.ok(courseService.adminCount());
    }
}
