package com.yuedong.point.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 今日打卡状态。
 */
@Data
@Builder
@AllArgsConstructor
public class CheckinTodayVO {

    /** 今天是否已打卡 */
    private boolean checked;

    /** 连续打卡天数 */
    private Integer continuousDays;
}
