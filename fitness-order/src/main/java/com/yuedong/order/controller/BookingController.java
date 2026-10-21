package com.yuedong.order.controller;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.dto.CancelDTO;
import com.yuedong.order.model.dto.PayDTO;
import com.yuedong.order.model.vo.BookingDetailVO;
import com.yuedong.order.model.vo.BookingPreviewVO;
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

    /** 创建待支付订单（一次一门课，份数可以大于 1） */
    @PostMapping("/create")
    public Result<BookingVO> create(@Valid @RequestBody BookingCreateDTO dto) {
        return Result.ok(bookingService.create(UserContext.getUserId(), dto));
    }

    /**
     * 下单页试算：金额 + 这个份数下我有哪些券可用。
     *
     * <p>★这份接口<b>不写库、不核销券</b>，纯粹是"给用户看他将要付多少"。
     * 用户每改一次份数、每换一张券都要重新打它——因为"能不能用"背后有五套规则
     * （金额门槛、有效期窗口、课程白名单、门店白名单、限领状态），
     * 这些规则<b>只允许在后端有一份定义</b>。前端复制一份的必然结果是
     * "页面显示可用、点提交报错"。</p>
     */
    @GetMapping("/preview")
    public Result<BookingPreviewVO> preview(@RequestParam Long courseId,
                                            @RequestParam(required = false) Long storeId,
                                            @RequestParam(required = false, defaultValue = "1") Integer quantity,
                                            @RequestParam(required = false) Long couponRecordId,
                                            @RequestParam(required = false) Long activityId) {
        // ★storeId 改成可选：秒杀态的门店由活动决定（抢的就是这家店的名额），
        //   秒杀列表里并不带 storeId，硬要前端传等于逼它去猜。
        //   普通态仍然必须有门店——服务层会拦，不在这里放开成"谁都能不传"。
        return Result.ok(bookingService.preview(UserContext.getUserId(), courseId, storeId,
                quantity, couponRecordId, activityId));
    }

    /**
     * 我的订单列表。
     *
     * @param tab ALL/UNPAID/PAID/CANCELED/REFUNDED/WAIT_USE/BOOKED/FINISHED/TO_RATE/EXPIRED
     */
    /**
     * 我的订单列表（分页）。
     *
     * @param tab  ALL / UNPAID / PAID / CANCELED / REFUNDED /
     *             WAIT_USE / BOOKED / FINISHED / TO_RATE / EXPIRED
     * @param page 页码，从 1 开始
     * @param size 每页条数，默认 10，服务端上限 50
     */
    @GetMapping("/list")
    public Result<PageDTO<BookingVO>> list(@RequestParam(required = false) String tab,
                                          @RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(bookingService.list(UserContext.getUserId(), tab, page, size));
    }

    /** 订单详情（含嵌套凭证与其下的预约记录） */
    @GetMapping("/detail")
    public Result<BookingDetailVO> detail(@RequestParam String orderNo) {
        return Result.ok(bookingService.detail(UserContext.getUserId(), orderNo));
    }

    /**
     * 模拟支付：把这一张单置为已支付并出票。
     *
     * <p>重复点"确认支付"不会出两次票：状态流转是
     * {@code UPDATE ... WHERE status='UNPAID'}，第二次影响 0 行直接报"状态已变更"。</p>
     */
    @PostMapping("/pay")
    public Result<Void> pay(@Valid @RequestBody PayDTO dto) {
        bookingService.pay(UserContext.getUserId(), dto.getOrderNo());
        return Result.ok();
    }

    /** 取消未支付订单 */
    @PostMapping("/cancel")
    public Result<Void> cancel(@Valid @RequestBody CancelDTO dto) {
        bookingService.cancel(UserContext.getUserId(), dto.getOrderNo());
        return Result.ok();
    }
}
