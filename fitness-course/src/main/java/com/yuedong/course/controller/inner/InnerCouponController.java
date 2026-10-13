package com.yuedong.course.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 优惠券内部/管理接口。签名与 fitness-course-api 的 CouponClient / CourseAdminClient 对应。
 *
 * <p>usable/use/refund 供订单服务 Feign 调用；
 * admin/page·save·end 供管理端后端服务（fitness-admin-server）调用。</p>
 */
@RestController
@RequestMapping("/inner/coupon")
@RequiredArgsConstructor
public class InnerCouponController {

    private final CouponService couponService;

    /** 校验持券可用性（amount = 订单原价） */
    @GetMapping("/info")
    public Result<CouponUsableDTO> info(@RequestParam Long recordId,
                                        @RequestParam Long userId,
                                        @RequestParam BigDecimal amount) {
        return Result.ok(couponService.usableInfo(recordId, userId, amount));
    }

    /** 核销用券（UNUSED → USED，绑定订单号） */
    @PostMapping("/use")
    public Result<Boolean> use(@RequestParam Long recordId,
                               @RequestParam Long userId,
                               @RequestParam String orderNo) {
        return Result.ok(couponService.use(recordId, userId, orderNo));
    }

    /** 退券（订单取消/超时回退为未使用） */
    @PostMapping("/refund")
    public Result<Boolean> refund(@RequestParam Long recordId) {
        return Result.ok(couponService.refund(recordId));
    }

    /** 管理端：券分页 */
    @GetMapping("/admin/page")
    public Result<PageDTO<CouponAdminDTO>> adminPage(@RequestParam(defaultValue = "1") Integer page,
                                                     @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(couponService.adminPage(page, size));
    }

    /** 管理端：创建/编辑券 */
    @PostMapping("/admin/save")
    public Result<Void> adminSave(@Valid @RequestBody CouponSaveDTO dto) {
        couponService.adminSave(dto);
        return Result.ok();
    }

    /** 管理端：作废券 */
    @PostMapping("/admin/end")
    public Result<Void> adminEnd(@RequestParam Long id) {
        couponService.adminEnd(id);
        return Result.ok();
    }
}
