package com.yuedong.point.model.vo;

import lombok.Data;

/**
 * 月度积分汇总行（内部用，不直接给前端）。
 *
 * <p>归档任务从 {@code t_points_log} 按 user_id 汇总本月获得的积分，
 * 这个对象只承接两列：谁、多少分。</p>
 */
@Data
public class RankScoreRow {

    private Long userId;

    /** 本月获得的积分（只累加正数；兑换扣减不影响“赚了多少分”的排名） */
    private Integer score;
}
