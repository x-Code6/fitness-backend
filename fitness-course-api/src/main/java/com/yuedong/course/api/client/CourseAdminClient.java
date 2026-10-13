package com.yuedong.course.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.ScheduleDTO;
import com.yuedong.course.api.dto.SeckillAdminDTO;
import com.yuedong.course.api.dto.SeckillSaveDTO;
import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.course.api.dto.StoreSaveDTO;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 课程服务管理端契约（fitness-admin-server 聚合调用：课程/排期/秒杀/优惠券/门店五域）。
 */
@FeignClient(name = "fitness-course")
public interface CourseAdminClient {

    // ---------- 课程 ----------

    /** 管理端：课程分页（类型/关键词筛选） */
    @GetMapping("/inner/course/admin/page")
    Result<PageDTO<CourseAdminDTO>> coursePage(@RequestParam("type") String type,
                                               @RequestParam("keyword") String keyword,
                                               @RequestParam("page") Integer page,
                                               @RequestParam("size") Integer size);

    /** 管理端：创建/编辑课程 */
    @PostMapping("/inner/course/admin/save")
    Result<Void> courseSave(@Valid @RequestBody CourseSaveDTO dto);

    /** 管理端：上架/下架 */
    @PostMapping("/inner/course/admin/status")
    Result<Void> courseStatus(@RequestParam("id") Long id, @RequestParam("status") Integer status);

    /** 管理端：删除课程 */
    @PostMapping("/inner/course/admin/delete")
    Result<Void> courseDelete(@RequestParam("id") Long id);

    // ---------- 排期 ----------

    /** 管理端：课程的全部场次（含历史） */
    @GetMapping("/inner/course/schedule/list")
    Result<List<ScheduleDTO>> scheduleList(@RequestParam("courseId") Long courseId);

    /** 管理端：新增场次 */
    @PostMapping("/inner/course/schedule/save")
    Result<Void> scheduleSave(@RequestParam("courseId") Long courseId,
                              @RequestParam("scheduleTime") String scheduleTime,
                              @RequestParam("capacity") Integer capacity);

    /** 管理端：删除场次 */
    @PostMapping("/inner/course/schedule/delete")
    Result<Void> scheduleDelete(@RequestParam("scheduleId") Long scheduleId);

    // ---------- 秒杀活动 ----------

    /** 管理端：全部活动列表（含课程名） */
    @GetMapping("/inner/seckill/admin/list")
    Result<List<SeckillAdminDTO>> seckillList();

    /** 管理端：创建活动 */
    @PostMapping("/inner/seckill/admin/save")
    Result<Void> seckillSave(@Valid @RequestBody SeckillSaveDTO dto);

    /** 管理端：提前结束活动 */
    @PostMapping("/inner/seckill/admin/end")
    Result<Void> seckillEnd(@RequestParam("id") Long id);

    // ---------- 优惠券 ----------

    /** 管理端：券分页 */
    @GetMapping("/inner/coupon/admin/page")
    Result<PageDTO<CouponAdminDTO>> couponPage(@RequestParam("page") Integer page,
                                               @RequestParam("size") Integer size);

    /** 管理端：创建/编辑券 */
    @PostMapping("/inner/coupon/admin/save")
    Result<Void> couponSave(@Valid @RequestBody CouponSaveDTO dto);

    /** 管理端：作废券 */
    @PostMapping("/inner/coupon/admin/end")
    Result<Void> couponEnd(@RequestParam("id") Long id);

    // ---------- 门店 ----------

    /** 管理端：门店列表（全量） */
    @GetMapping("/inner/store/list")
    Result<List<StoreDTO>> storeList();

    /** 管理端：新增/编辑门店 */
    @PostMapping("/inner/store/save")
    Result<Void> storeSave(@Valid @RequestBody StoreSaveDTO dto);

    /** 管理端：删除门店 */
    @PostMapping("/inner/store/delete")
    Result<Void> storeDelete(@RequestParam("id") Long id);
}
