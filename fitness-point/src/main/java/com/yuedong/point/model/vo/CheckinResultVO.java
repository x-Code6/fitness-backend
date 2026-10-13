package com.yuedong.point.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 打卡结果（字段与前端 Mock 对齐）。
 */
@Data
@AllArgsConstructor
public class CheckinResultVO {

    /** 本次获得积分（+10） */
    private Integer points;

    /** 连续打卡天数 */
    private Integer continuousDays;

    /** 最新积分余额 */
    private Integer totalPoints;
}
