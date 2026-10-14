package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.CourseStore;

/**
 * 课程 × 门店 Mapper。
 *
 * <p>只用 BaseMapper 的能力：查某门的可售门店用
 * {@code selectList(LambdaQueryWrapper.in(CourseStore::getCourseId, ids))} 一次取回，
 * 再在 Java 里按 courseId 分组统计门店数。
 * 之所以不写 {@code GROUP BY} 的动态 SQL（{@code <script>+<foreach>}）：
 * 列表页一次也就十几门课、几十行关联记录，内存分组的代价可以忽略，
 * 而注解里拼动态 SQL 的可读性差很多——本项目按"可读性优先"的约定选前者。</p>
 *
 * <p>★索引提示：{@code uk_course_store(course_id, store_id)} 的最左前缀服务
 * "按 course_id 查这家课在哪些店"；列表页反过来要"按 store_id 查本店课程"时，
 * 走的是建表时另加的 {@code idx_store_sell(store_id, status, sell_end_time)}。</p>
 */
public interface CourseStoreMapper extends BaseMapper<CourseStore> {
}
