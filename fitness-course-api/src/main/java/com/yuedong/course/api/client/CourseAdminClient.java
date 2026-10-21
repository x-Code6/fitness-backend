package com.yuedong.course.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.AdminCountDTO;
import com.yuedong.course.api.dto.BannerAdminDTO;
import com.yuedong.course.api.dto.BannerSaveDTO;
import com.yuedong.course.api.dto.CoachAdminDTO;
import com.yuedong.course.api.dto.CoachSaveDTO;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.CourseWindowAdminDTO;
import com.yuedong.course.api.dto.CourseWindowSaveDTO;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
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
 * 课程服务管理端契约：课程 / 课程可售门店 / 两层时间窗 / 教练 / 秒杀 / 优惠券 / 门店。
 *
 * <p>★v2 把"场次（schedule）"三个方法换成"可售门店（store-list / assign-store）"两个：
 * 管理端原来要给课程排时间，现在要给课程配"在哪家店卖、什么时间能买"。
 * 两层时间窗的<b>精细配置</b>由末尾的 {@code window/page} 与 {@code window/save} 负责（切片 L 已落地）；
 * {@code assign-store} 只建课程×门店关系并给一套默认窗口，避免"配了门店却没生效"。</p>
 */
@FeignClient(name = "fitness-course", contextId = "courseAdminClient")
public interface CourseAdminClient {

    // ---------- 课程 ----------

    /**
     * 课程分页。
     *
     * @param type    课程分类（瑜伽/搏击…），"全部"或不传表示不按分类筛
     * @param kind    SALE 可售课 / CONTENT 展示课
     * @param status  1 上架 / 0 下架
     * @param keyword 课程名模糊
     */
    @GetMapping("/inner/course/admin/page")
    Result<PageDTO<CourseAdminDTO>> coursePage(@RequestParam("type") String type,
                                               @RequestParam("kind") String kind,
                                               @RequestParam("status") Integer status,
                                               @RequestParam("keyword") String keyword,
                                               @RequestParam("page") Integer page,
                                               @RequestParam("size") Integer size);

    /** 新增/编辑课程 */
    @PostMapping("/inner/course/admin/save")
    Result<Void> courseSave(@Valid @RequestBody CourseSaveDTO dto);

    /** 上架/下架 */
    @PostMapping("/inner/course/admin/status")
    Result<Void> courseStatus(@RequestParam("id") Long id, @RequestParam("status") Integer status);

    /** 删除课程 */
    @PostMapping("/inner/course/admin/delete")
    Result<Void> courseDelete(@RequestParam("id") Long id);

    // ---------- 课程 × 可售门店 ----------

    /** 查某课程当前可售门店 id 列表（表单回显） */
    @GetMapping("/inner/course/admin/store-list")
    Result<List<Long>> courseStoreList(@RequestParam("courseId") Long courseId);

    /**
     * 整体替换某课程的可售门店。
     *
     * <p>★{@code storeIds} 传空数组表示"下架全部门店"，传 null 会被拒绝——
     * null 说明调用方漏传参数，和"我要清空"是两件事，混起来会做出
     * "本想保存却把门店配置清了"的事故。</p>
     */
    @PostMapping("/inner/course/admin/assign-store")
    Result<Void> courseAssignStore(@RequestParam("courseId") Long courseId,
                                   @RequestBody List<Long> storeIds);

    // ---------- 秒杀活动 ----------

    @GetMapping("/inner/seckill/admin/list")
    Result<List<SeckillAdminDTO>> seckillList();

    @PostMapping("/inner/seckill/admin/save")
    Result<Void> seckillSave(@Valid @RequestBody SeckillSaveDTO dto);

    @PostMapping("/inner/seckill/admin/end")
    Result<Void> seckillEnd(@RequestParam("id") Long id);

    // ---------- 优惠券 ----------

    @GetMapping("/inner/coupon/admin/page")
    Result<PageDTO<CouponAdminDTO>> couponPage(@RequestParam("page") Integer page,
                                               @RequestParam("size") Integer size);

    @PostMapping("/inner/coupon/admin/save")
    Result<Void> couponSave(@Valid @RequestBody CouponSaveDTO dto);

    @PostMapping("/inner/coupon/admin/end")
    Result<Void> couponEnd(@RequestParam("id") Long id);

    // ---------- 门店 ----------

    @GetMapping("/inner/store/list")
    Result<List<StoreDTO>> storeList();

    @PostMapping("/inner/store/save")
    Result<Void> storeSave(@Valid @RequestBody StoreSaveDTO dto);

    @PostMapping("/inner/store/delete")
    Result<Void> storeDelete(@RequestParam("id") Long id);

    // ---------- 教练 ----------

    /**
     * 教练分页（门店/状态/性别/关键词四个筛选位）。
     *
     * @param keyword 模糊匹配姓名/资质/简介/电话，四个字段用 OR 拼在一个括号里
     */
    @GetMapping("/inner/coach/admin/page")
    Result<PageDTO<CoachAdminDTO>> coachPage(@RequestParam("storeId") Long storeId,
                                             @RequestParam("status") Integer status,
                                             @RequestParam("gender") Integer gender,
                                             @RequestParam("keyword") String keyword,
                                             @RequestParam("page") Integer page,
                                             @RequestParam("size") Integer size);

    /** 新增/修改教练。★返回教练 id：新增后前端要立刻拿它去配课 */
    @PostMapping("/inner/coach/save")
    Result<Long> coachSave(@Valid @RequestBody CoachSaveDTO dto);

    /** 启用/停用 */
    @PostMapping("/inner/coach/admin/status")
    Result<Void> coachStatus(@RequestParam("id") Long id, @RequestParam("status") Integer status);

    /**
     * 删除教练（物理删 + 清配课）。
     *
     * <p>★"有没有未完成的预约"这个前置判断<b>不在这里</b>：预约表在 yd_order，
     * 课程服务查不到。由 admin-server 先问 order 再调这个接口 ——
     * 跨库校验交给 BFF 编排，比让 course 反向依赖 order 干净。</p>
     */
    @PostMapping("/inner/coach/admin/delete")
    Result<Void> coachDelete(@RequestParam("id") Long id);

    /** 某教练已配课程 id（表单回显） */
    @GetMapping("/inner/coach/course-ids")
    Result<List<Long>> coachCourseIds(@RequestParam("coachId") Long coachId);

    /** 整体替换某教练的可授课范围；body 传空数组=清空，null 被服务层拒绝 */
    @PostMapping("/inner/coach/assign-course")
    Result<Void> coachAssignCourse(@RequestParam("coachId") Long coachId,
                                   @RequestBody List<Long> courseIds);

    // ---------- 课程 × 门店两层时间窗 ----------

    /** 窗口分页（按课程或按门店筛，两个都不传就是全量） */
    @GetMapping("/inner/course/admin/window/page")
    Result<PageDTO<CourseWindowAdminDTO>> windowPage(@RequestParam("courseId") Long courseId,
                                                     @RequestParam("storeId") Long storeId,
                                                     @RequestParam("page") Integer page,
                                                     @RequestParam("size") Integer size);

    /** 保存某门课在某家店的售卖窗口与有效期 */
    @PostMapping("/inner/course/admin/window/save")
    Result<Void> windowSave(@Valid @RequestBody CourseWindowSaveDTO dto);

    /** 管理端看板：课程侧计数（在售课 / 可约教练 / 营业门店 / 今日应开放时段格子） */
    @GetMapping("/inner/course/admin/count")
    Result<AdminCountDTO> adminCount();

    // ---------- 活动页 ----------
    @GetMapping("/inner/course/banner/page")
    Result<PageDTO<BannerAdminDTO>> bannerPage(@RequestParam("page") Integer page,
                                               @RequestParam("size") Integer size);

    @PostMapping("/inner/course/banner/save")
    Result<Void> saveBanner(@RequestBody BannerSaveDTO dto);

    @PostMapping("/inner/course/banner/status")
    Result<Void> setBannerStatus(@RequestParam("id") Long id,
                                 @RequestParam("status") Integer status);
}
