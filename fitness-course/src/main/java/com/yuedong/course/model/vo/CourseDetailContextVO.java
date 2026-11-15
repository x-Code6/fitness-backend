package com.yuedong.course.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 课程详情“门店级上下文”缓存（60 秒）。
 *
 * <p>基础 {@link CourseVO} 缓存 30 分钟，只放稳定数据；评分、评论数、门店列表、
 * 教练数、门店规则这些允许短期不一致的字段放在这里，按 {@code courseId + storeId}
 * 维度缓存 60 秒。更新不主动删缓存，靠 TTL 收敛。</p>
 *
 * <p>★不要把这个对象写回课程级基础缓存：coachCount / 门店规则是门店维度的，
 * 写回 {@code course:detail:{courseId}} 会让 A 店的数据串给 B 店。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseDetailContextVO {

    private String ratingText;
    private Integer ratingCount;

    private List<CourseVO.StoreOption> stores;
    private Integer storeCount;

    private Integer commentCount;
    private Integer coachCount;
    private String latestFreeText;

    private List<String> ruleTexts;
    private String ruleSummary;
}
