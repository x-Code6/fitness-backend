package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.client.BookingClient;
import com.yuedong.order.api.dto.BookingDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 核销工作台：核销预约单 + 最近核销记录。
 */
@RestController
@RequestMapping("/admin/verify")
@RequiredArgsConstructor
public class VerifyAdminController {

    private final BookingClient orderClient;

    /** 核销（仅"已预约"订单可核销，一码一次）；返回核销成功的订单（含 userId/课程信息） */
    @OperateLog(module = "核销工作台", operation = "核销预约单")
    @PostMapping("/do")
    public Result<BookingDTO> verify(@RequestParam String code) {
        return orderClient.verify(code);
    }

    /** 最近核销记录 */
    @GetMapping("/recent")
    public Result<List<BookingDTO>> recent() {
        return orderClient.verifiedRecent();
    }
}
