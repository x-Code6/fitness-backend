package com.yuedong.order.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.ReservationAdminDTO;
import com.yuedong.order.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预约（时段轴）内部接口，只服务管理端：分页、到店核销、删教练前的占用判断。
 */
@RestController
@RequestMapping("/inner/reservation")
@RequiredArgsConstructor
@Validated
public class InnerReservationController {

    private final ReservationService reservationService;

    /** 管理端：预约分页（状态/教练/门店/某一天/关键词） */
    @GetMapping("/admin/page")
    public Result<PageDTO<ReservationAdminDTO>> adminPage(@RequestParam(required = false) String status,
                                                          @RequestParam(required = false) Long coachId,
                                                          @RequestParam(required = false) Long storeId,
                                                          @RequestParam(required = false) String date,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(defaultValue = "1") Integer page,
                                                          @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(reservationService.adminPage(status, coachId, storeId, date, keyword, page, size));
    }

    /**
     * 管理端：按核销码登记到店。
     * <p>★这里就是核查文档里"核销逻辑早就写好了、但没有对外入口"的那一条：
     * 服务层的 {@code verify} 一直存在（用户侧自己看码），
     * 本轮补的是<b>前台店员用的入口</b>。逻辑没有第二份。</p>
     */
    @PostMapping("/admin/verify")
    public Result<ReservationAdminDTO> adminVerify(@RequestParam String verifyCode) {
        return Result.ok(reservationService.adminVerify(verifyCode));
    }

    /** 管理端：某教练是否还有未开始的预约（course 服务删教练前要来问一次） */
    @GetMapping("/admin/coach-has-future")
    public Result<Boolean> coachHasFuture(@RequestParam Long coachId) {
        return Result.ok(reservationService.hasFutureByCoach(coachId));
    }
}
