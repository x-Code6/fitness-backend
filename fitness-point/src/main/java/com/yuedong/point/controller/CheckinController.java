package com.yuedong.point.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.point.model.vo.CheckinCalendarVO;
import com.yuedong.point.model.vo.CheckinResultVO;
import com.yuedong.point.model.vo.CheckinTodayVO;
import com.yuedong.point.model.vo.PointsLogVO;
import com.yuedong.point.service.CheckinService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 打卡与积分接口（需登录）。
 *
 * <p>网关路由：/api/checkin/** → 本服务 /checkin/**（路径与前端 Mock 完全一致）。</p>
 */
@RestController
@RequestMapping("/checkin")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;

    /** 今日打卡状态 */
    @GetMapping("/today")
    public Result<CheckinTodayVO> today() {
        return Result.ok(checkinService.today(UserContext.getUserId()));
    }

    /**
     * 每日打卡（SETBIT → BITFIELD 连续天数 → 记账 → ZINCRBY 月榜）。
     */
    @PostMapping("/do")
    public Result<CheckinResultVO> doCheckin() {
        return Result.ok(checkinService.doCheckin(UserContext.getUserId()));
    }

    /** 当月打卡日历 */
    @GetMapping("/calendar")
    public Result<CheckinCalendarVO> calendar() {
        return Result.ok(checkinService.calendar(UserContext.getUserId()));
    }

    /** 积分明细（最近 50 条） */
    @GetMapping("/points/log")
    public Result<List<PointsLogVO>> pointsLog() {
        return Result.ok(checkinService.pointsLog(UserContext.getUserId()));
    }
}
