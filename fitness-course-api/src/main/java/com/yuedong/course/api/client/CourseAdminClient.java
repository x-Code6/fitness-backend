package com.yuedong.course.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
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
 * 课程服务管理端契约：课程 / 课程可售门店 / 秒杀 / 优惠券 / 门店 五域。
 *
 * <p>★v2 把"场次（schedule）"三个方法换成"可售门店（store-list / assign-store）"两个：
 * 管理端原来要给课程排场次，现在要给课程配"在哪家店卖、什么时间能买"。
 * 时间窗的精细配置本期还没做（{@code assign-store} 用默认窗口），
 * 但"哪几家店在卖"这一步必须先能配，否则课程建好了也买不了。</p>
 */
@FeignClient(name = "fitness-course")
public interface CourseAdminClient {

    // ---------- 课程 ----------

    /** 课程分页（类型/关键词筛选） */
    @GetMapping("/inner/course/admin/page")
    Result<PageDTO<CourseAdminDTO>> coursePage(@RequestParam("type") String type,
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
}
