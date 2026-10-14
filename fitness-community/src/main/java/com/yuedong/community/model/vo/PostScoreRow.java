package com.yuedong.community.model.vo;

import lombok.Data;

/**
 * 推荐流的一行读结果（内部用，<b>不直接给前端</b>）。
 *
 * <p>★为什么不复用 {@link PostVO} 带个 score 字段：
 * VO 是对外契约，多一个前端不会用的字段就等于对外承诺了它，将来删不掉；
 * 而这种"SQL 算完分只要 id + score，回表再组装"的中间形状，
 * 用最朴素的小对象写最清楚。</p>
 */
@Data
public class PostScoreRow {

    /** 动态 id */
    private Long postId;

    /** 热度分（只为算游标与排序，不显示给用户） */
    private Double score;
}
