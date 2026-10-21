package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端看板的<b>课程侧</b>计数（看板其余数字来自 order / user）。
 *
 * <p>★{@code todaySlotTotal} 是"今天应该开放多少个教练格子"：
 * 只算<b>状态可约</b>且<b>今天在他可约星期里</b>的教练，每人 {@code workEndHour - workStartHour} 个整点。
 * 少算"今天不上班"的教练是故意的——否则占用率会被分母稀释，
 * 运营看到的"今天只用了 30%"其实是"今天一半教练休息"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCountDTO {

    /** 在售课程数（status=1） */
    private Integer courseCount;

    /** 可约教练数（status=1） */
    private Integer coachCount;

    /** 营业中门店数 */
    private Integer storeCount;

    /** 今日应开放时段格子数（见类注释的口径） */
    private Integer todaySlotTotal;
}
