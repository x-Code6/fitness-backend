package com.yuedong.order.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.AdminStatDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.api.dto.OrderAdminDetailDTO;
import com.yuedong.order.service.AdminStatService;
import com.yuedong.order.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 订单服务内部接口（管理端订单管理经 admin-server 转发调用；网关拒绝外部直访 /inner/**）。
 * 签名与 fitness-order-api 的 BookingClient 一一对应。
 */
@RestController
@RequestMapping("/inner/booking")
@RequiredArgsConstructor
@Validated
public class InnerBookingController {

    private final BookingService bookingService;
    private final AdminStatService adminStatService;

    /** 管理端：订单分页（全用户，状态/关键词筛选） */
    @GetMapping("/admin/page")
    public Result<PageDTO<BookingDTO>> adminPage(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") Integer page,
                                                 @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(bookingService.adminPage(status, keyword, page, size));
    }

    /**
     * 管理端：代客取消未支付订单。
     *
     * <p>★v2 移除了 {@code /verify} 与 {@code /verified/recent}：核销的对象是<b>预约单</b>
     * （教练确认这个人来上过课），不是订单。它们随预约分支一起回来，
     * 挂到 {@code /inner/reservation/**} 下。留在订单上就是把三条状态机轴又塞回一张表。</p>
     */
    @PostMapping("/admin/cancel")
    public Result<Void> adminCancel(@RequestParam String orderNo) {
        bookingService.adminCancel(orderNo);
        return Result.ok();
    }

    /** 管理端：订单详情（订单 + 凭证 + 预约三条轴一次给全） */
    @GetMapping("/admin/detail")
    public Result<OrderAdminDetailDTO> adminDetail(@RequestParam String orderNo) {
        return Result.ok(bookingService.adminDetail(orderNo));
    }

    /** 管理端：看板统计（订单侧那一半；课程/user 各补另一半，由 admin-server 拼） */
    @GetMapping("/admin/stat")
    public Result<AdminStatDTO> adminStat() {
        return Result.ok(adminStatService.stat());
    }
}
