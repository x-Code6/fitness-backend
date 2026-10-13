package com.yuedong.order.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.BookingDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 订单服务契约（管理端订单管理/核销工作台 Feign 调用）。
 */
@FeignClient(name = "fitness-order")
public interface BookingClient {

    /** 按核销码核销预约单（BOOKED → FINISHED，一码一次） */
    @PostMapping("/inner/booking/verify")
    Result<BookingDTO> verify(@RequestParam("code") String code);

    /** 管理端：订单分页（全用户，状态/关键词筛选） */
    @GetMapping("/inner/booking/admin/page")
    Result<PageDTO<BookingDTO>> orderPage(@RequestParam("status") String status,
                                          @RequestParam("keyword") String keyword,
                                          @RequestParam("page") Integer page,
                                          @RequestParam("size") Integer size);

    /** 管理端：代客取消 */
    @PostMapping("/inner/booking/admin/cancel")
    Result<Void> adminCancel(@RequestParam("orderNo") String orderNo);

    /** 管理端：最近核销记录 */
    @GetMapping("/inner/booking/verified/recent")
    Result<List<BookingDTO>> verifiedRecent();
}
