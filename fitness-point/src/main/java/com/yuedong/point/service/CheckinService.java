package com.yuedong.point.service;

import com.yuedong.point.api.dto.PointBriefDTO;
import com.yuedong.point.model.vo.CheckinCalendarVO;
import com.yuedong.point.model.vo.CheckinTodayVO;
import com.yuedong.point.model.vo.PointsLogVO;

import java.util.List;

/**
 * 打卡与积分业务接口（本服务拥有 t_member_points / t_points_log 域）。
 */
public interface CheckinService {

    /** 今天是否已打卡（Bitmap GETBIT） */
    CheckinTodayVO today(Long userId);

    /**
     * 每日打卡：SETBIT → BITFIELD 反推连续天数 → 记流水/加余额 → ZINCRBY 月榜。
     *
     * @return points=本次得分，continuousDays=连续天数，totalPoints=最新余额
     */
    com.yuedong.point.model.vo.CheckinResultVO doCheckin(Long userId);

    /** 当月打卡日历 */
    CheckinCalendarVO calendar(Long userId);

    /** 积分明细（最近 50 条） */
    List<PointsLogVO> pointsLog(Long userId);

    /** 内部接口：会员积分简况批量查询（管理端会员页聚合用） */
    List<PointBriefDTO> brief(List<Long> userIds);
}
