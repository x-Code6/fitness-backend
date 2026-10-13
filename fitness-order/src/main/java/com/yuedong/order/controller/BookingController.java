package com.yuedong.order.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.dto.CancelDTO;
import com.yuedong.order.model.dto.PayDTO;
import com.yuedong.order.model.vo.BookingVO;
import com.yuedong.order.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预约订单接口（需登录）。
 *
 * <p>网关路由：/api/booking/** → 本服务 /booking/**。</p>
 */
@RestController
@RequestMapping("/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /** 创建预约：查课程 → 去重 → 占名额 → 建 UNPAID 单 → 15 分钟延迟取消 */
    @PostMapping("/create")
    public Result<BookingVO> create(@Valid @RequestBody BookingCreateDTO dto) {
        return Result.ok(bookingService.create(UserContext.getUserId(), dto));
    }

    /** 我的预约列表（status 可选：UNPAID/BOOKED/FINISHED/CANCELED/ALL） */
    @GetMapping("/list")
    public Result<List<BookingVO>> list(@RequestParam(required = false) String status) {
        return Result.ok(bookingService.list(UserContext.getUserId(), status));
    }

    /**
     * 模拟支付（真实项目替换为：创建支付单 → 用户付款 → 支付回调驱动状态变更）。
     *
     * @param dto 含订单号
     */
    @PostMapping("/pay")
    public Result<Void> pay(@Valid @RequestBody PayDTO dto) {
        bookingService.pay(UserContext.getUserId(), dto.getId());
        return Result.ok();
    }

    /**
     * 取消预约（UNPAID/BOOKED 可取消，释放名额）。
     *
     * @param dto 含订单号
     */
    @PostMapping("/cancel")
    public Result<Void> cancel(@Valid @RequestBody CancelDTO dto) {
        bookingService.cancel(UserContext.getUserId(), dto.getId());
        return Result.ok();
    }
}
