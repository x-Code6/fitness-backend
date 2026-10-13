package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优惠券管理。
 */
@RestController
@RequestMapping("/admin/coupon")
@RequiredArgsConstructor
public class CouponAdminController {

    private final CourseAdminClient courseClient;

    /** 券分页 */
    @GetMapping("/page")
    public Result<PageDTO<CouponAdminDTO>> page(@RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "10") Integer size) {
        return courseClient.couponPage(page, size);
    }

    @OperateLog(module = "优惠券", operation = "创建/编辑优惠券")
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody CouponSaveDTO dto) {
        return courseClient.couponSave(dto);
    }

    @OperateLog(module = "优惠券", operation = "作废优惠券")
    @PostMapping("/end")
    public Result<Void> end(@RequestParam Long id) {
        return courseClient.couponEnd(id);
    }
}
