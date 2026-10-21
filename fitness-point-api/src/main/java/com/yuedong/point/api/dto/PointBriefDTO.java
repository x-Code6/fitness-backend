package com.yuedong.point.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会员积分简况 DTO（管理端会员页聚合用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointBriefDTO {

    private Long userId;

    /** 积分余额 */
    private Integer balance;

    /** 累计打卡天数 */
    private Integer totalCheckin;

    /** 连续打卡天数 */
    private Integer continuousDays;
}
