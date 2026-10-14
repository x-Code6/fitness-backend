package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.adminserver.model.vo.ReservationAdminVO;
import com.yuedong.adminserver.service.AdminVoConverter;
import com.yuedong.adminserver.service.MemberNameLoader;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.client.BookingClient;
import com.yuedong.order.api.dto.ReservationAdminDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 预约管理与到店核销（时段轴）。
 *
 * <p>★核销这件事在 v2 只是"登记人到店里"：扣次由到点任务按 {@code end_time} 做（D5）。
 * 所以这个按钮不产生任何权益变化，点错了也不会扣掉用户一次课，
 * 最坏情况只是"到店记录不准"。这是把核销从业务闸门降级成流水记录后的直接收益。</p>
 */
@RestController
@RequestMapping("/admin/reservation")
@RequiredArgsConstructor
public class ReservationAdminController {

    private final BookingClient orderClient;
    private final MemberNameLoader memberNameLoader;
    private final AdminVoConverter converter;

    /**
     * 预约分页。
     *
     * @param status  BOOKED / FINISHED / CANCELED
     * @param coachId 按教练筛
     * @param storeId 按门店筛
     * @param date    某一天 yyyy-MM-dd（按 start_time 落在当天判）
     * @param keyword 预约单号 / 订单号 / 课程名 / 教练名 模糊（<b>不含昵称</b>，同凭证页那条理由）
     */
    @GetMapping("/page")
    public Result<PageDTO<ReservationAdminVO>> page(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long coachId,
                                                    @RequestParam(required = false) Long storeId,
                                                    @RequestParam(required = false) String date,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "1") Integer page,
                                                    @RequestParam(defaultValue = "10") Integer size) {
        PageDTO<ReservationAdminDTO> data = orderClient
                .reservationPage(status, coachId, storeId, date, keyword, page, size).getData();
        if (data == null || data.getList().isEmpty()) {
            return Result.ok(PageDTO.of(List.of(), data == null ? 0 : data.getTotal()));
        }

        Map<Long, String> names = memberNameLoader.mapOf(data.getList().stream()
                .map(ReservationAdminDTO::getUserId).toList());
        List<ReservationAdminVO> rows = data.getList().stream()
                .map(row -> converter.toVO(row, names))
                .toList();
        return Result.ok(PageDTO.of(rows, data.getTotal()));
    }

    /**
     * 到店核销：前台输入用户出示的核销码。
     * <p>★返回核销后的那一行，工作台要立刻显示"这是谁的哪节课"——
     * 只回一个 true 的话，店员看不到自己刚核销了什么东西。</p>
     */
    @OperateLog(module = "预约核销", operation = "到店核销")
    @PostMapping("/verify")
    public Result<ReservationAdminVO> verify(@RequestParam String verifyCode) {
        ReservationAdminDTO row = orderClient.reservationVerify(verifyCode).getData();
        if (row == null) {
            return Result.ok(null);
        }
        Map<Long, String> names = memberNameLoader.mapOf(List.of(row.getUserId()));
        return Result.ok(converter.toVO(row, names));
    }
}
