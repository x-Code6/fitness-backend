package com.yuedong.point.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 打卡日历（当月已打卡日期 + 天数统计，字段与前端 Mock 对齐）。
 */
@Data
@Builder
@AllArgsConstructor
public class CheckinCalendarVO {

    /** 已打卡日期（ISO 格式，如 2026-10-02） */
    private List<String> days;

    private Integer continuousDays;

    private Integer totalDays;
}
