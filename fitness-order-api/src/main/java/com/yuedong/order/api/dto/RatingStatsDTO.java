package com.yuedong.order.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评分汇总（跨服务契约）：课程列表、门店列表要显示的"4.8 分 / 12 条"就来自这里。
 *
 * <p>★<b>为什么由 order 算贝叶斯而不是每个消费方自己算</b>：
 * 展示用算术平均、排序用贝叶斯 {@code (C*M + sum) / (C + n)}，
 * 其中 C（可信样本数）和 M（全站均值）是<b>业务参数</b>。
 * 参数放 course 服务一份、放 admin 服务一份，改一次要同步三处，
 * 排序分和展示分就会来自两套参数。放在数据所在的服务里，全局只有一个定义。</p>
 *
 * <p>两个分数都算好带出去，消费方<b>不做任何算术</b>。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RatingStatsDTO {

    /** 目标 id（门店/课程/教练任一，由请求时的 type 决定它是哪种） */
    private Long targetId;

    /** 分数之和（原始量，便于排查"为什么显示 4.8"） */
    private Integer ratingSum;

    /** 正常状态的评价条数。★0 时两个分数都给 null，用 null 表达"没人评过"而不是 0 分 */
    private Integer ratingCount;

    /** 算术平均，一位小数，如 "4.8"。给用户看口碑用这个 */
    private String avgText;

    /** 贝叶斯平滑分。★只用于排序/优选标签，不显示给用户——用户看不懂"为什么 4.2 分只有 1 条评价 */
    private Double bayesianScore;
}
