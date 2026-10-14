package com.yuedong.order.controller;

import com.yuedong.common.idempotent.Idempotent;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.dto.ReservationCancelDTO;
import com.yuedong.order.model.dto.ReservationCreateDTO;
import com.yuedong.order.model.vo.ReservationVO;
import com.yuedong.order.model.vo.slot.SlotPageVO;
import com.yuedong.order.service.ReservationService;
import com.yuedong.order.service.SlotService;
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
 * 预约与时段接口（需登录）。
 *
 * <p>网关路由：{@code /api/reservation/**} → 本服务 {@code /reservation/**}。</p>
 *
 * <p>★<b>时段查询为什么放在 order 域而不是 course 域</b>（也和 docs 4.3.2 表格里写的
 * {@code /api/course/slots} 不同）：格子状态来自 {@code yd_order.t_coach_busy}，
 * 放课程侧就得跨库取占用数据，要么 Feign 要么双写。
 * <b>接口位置由数据归属决定，不由"这个功能看起来像课程的一部分"决定</b>。
 * 这条判断在前端重构时已经按同样的理由定下，本处保持一致。</p>
 */
@RestController
@RequestMapping("/reservation")
@RequiredArgsConstructor
public class ReservationController {

    private final SlotService slotService;
    private final ReservationService reservationService;

    /**
     * 时段页唯一的一个读接口。
     *
     * @param courseId 课程
     * @param storeId  门店（★凭证认店，不传就无从判断能约哪家店）
     * @param date     yyyy-MM-dd，不传按今天
     */
    @GetMapping("/slots")
    public Result<SlotPageVO> slots(@RequestParam Long courseId,
                                    @RequestParam Long storeId,
                                    @RequestParam(required = false) String date) {
        return Result.ok(slotService.slots(UserContext.getUserId(), courseId, storeId, date));
    }

    /**
     * 创建预约。
     *
     * <p>★加幂等的理由（三档里的"值得防"）：双击会发出两个请求，
     * 第一个占住格子成功，第二个撞唯一索引 → 用户看到"该时段刚刚被预约"，
     * 一脸莫名其妙。{@code @Idempotent} 在 5 秒窗口里把第二次提交直接挡掉，
     * 而真正的最终防线仍然是 {@code uk_coach_start}。</p>
     */
    @Idempotent(prefix = "reservationCreate", expireSecond = 5)
    @PostMapping("/create")
    public Result<ReservationVO> create(@Valid @RequestBody ReservationCreateDTO dto) {
        return Result.ok(reservationService.book(UserContext.getUserId(), dto));
    }

    /** 取消预约（不扣次，时段自动释放）。规则："距开课 1 小时内不可取消"由数据库判 */
    @PostMapping("/cancel")
    public Result<Void> cancel(@Valid @RequestBody ReservationCancelDTO dto) {
        reservationService.cancel(UserContext.getUserId(), dto.getReservationNo());
        return Result.ok();
    }

    /** 我的预约列表。status：ALL/BOOKED/FINISHED/CANCELED，不传按 ALL */
    @GetMapping("/mine")
    public Result<List<ReservationVO>> mine(@RequestParam(required = false) String status) {
        return Result.ok(reservationService.mine(UserContext.getUserId(), status));
    }

    /** 预约详情（核销码、取消截止、门店地址） */
    @GetMapping("/detail")
    public Result<ReservationVO> detail(@RequestParam String reservationNo) {
        return Result.ok(reservationService.detail(UserContext.getUserId(), reservationNo));
    }
}
