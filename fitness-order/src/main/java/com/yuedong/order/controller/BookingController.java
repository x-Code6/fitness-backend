package com.yuedong.order.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.dto.CancelDTO;
import com.yuedong.order.model.dto.PayDTO;
import com.yuedong.order.model.vo.BookingDetailVO;
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
 * 购买订单接口（需登录）。
 *
 * <p>网关路由：{@code /api/booking/**} → 本服务 {@code /booking/**}。</p>
 *
 * <p>★这里只有<b>钱</b>这一条轴：下单、支付、取消、看列表与详情。
 * "约教练的时间"在 {@code /reservation/**}，"还剩几次"在 {@code /entitlement/**}。
 * 三条轴分在三个接口组里，是 v2 拆表之后接口层应有的样子——
 * 一个 {@code /booking/cancel} 同时能干掉预约、退名额、改核销状态的时代已经过去了。</p>
 */
@RestController
@RequestMapping("/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /** 创建待支付订单（单买，不走购物车） */
    @PostMapping("/create")
    public Result<BookingVO> create(@Valid @RequestBody BookingCreateDTO dto) {
        return Result.ok(bookingService.create(UserContext.getUserId(), dto));
    }

    /**
     * 我的订单列表。
     *
     * @param tab ALL/UNPAID/PAID/CANCELED/REFUNDED/WAIT_USE/BOOKED/FINISHED/TO_RATE/EXPIRED
     */
    @GetMapping("/list")
    public Result<List<BookingVO>> list(@RequestParam(required = false) String tab) {
        return Result.ok(bookingService.list(UserContext.getUserId(), tab));
    }

    /** 订单详情（含嵌套凭证与其下的预约记录） */
    @GetMapping("/detail")
    public Result<BookingDetailVO> detail(@RequestParam String orderNo) {
        return Result.ok(bookingService.detail(UserContext.getUserId(), orderNo));
    }

    /**
     * 模拟支付。
     *
     * <p>★{@code orderNo} 与 {@code parentNo} 二选一：收银台在购物车合并下单的场景下
     * 只有组号，需要一次付清同组全部订单。</p>
     */
    @PostMapping("/pay")
    public Result<Void> pay(@Valid @RequestBody PayDTO dto) {
        bookingService.pay(UserContext.getUserId(), dto.getOrderNo(), dto.getParentNo());
        return Result.ok();
    }

    /** 取消未支付订单（同样支持整组取消） */
    @PostMapping("/cancel")
    public Result<Void> cancel(@Valid @RequestBody CancelDTO dto) {
        bookingService.cancel(UserContext.getUserId(), dto.getOrderNo(), dto.getParentNo());
        return Result.ok();
    }
}
