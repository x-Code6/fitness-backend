package com.yuedong.order.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.service.BookingService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单服务内部接口（管理端核销工作台/订单管理经后端转发调用；网关拒绝外部直访 /inner/**）。
 * 签名与 fitness-order-api 的 BookingClient 一一对应。
 */
@RestController
@RequestMapping("/inner/booking")
@RequiredArgsConstructor
@Validated
public class InnerBookingController {

    private final BookingService bookingService;

    /**
     * 按核销码核销预约单：BOOKED → FINISHED（一码一次）。
     *
     * @param code 10 位核销码
     */
    @PostMapping("/verify")
    public Result<BookingDTO> verify(@RequestParam String code) {
        return Result.ok(bookingService.verifyByCode(code));
    }

    /** 管理端：订单分页（全用户，状态/关键词筛选） */
    @GetMapping("/admin/page")
    public Result<PageDTO<BookingDTO>> adminPage(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") Integer page,
                                                 @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(bookingService.adminPage(status, keyword, page, size));
    }

    /** 管理端：代客取消 */
    @PostMapping("/admin/cancel")
    public Result<Void> adminCancel(@RequestParam String orderNo) {
        bookingService.adminCancel(orderNo);
        return Result.ok();
    }

    /** 管理端：最近核销记录 */
    @GetMapping("/verified/recent")
    public Result<List<BookingDTO>> verifiedRecent() {
        return Result.ok(bookingService.verifiedRecent());
    }
}
