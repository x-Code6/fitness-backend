package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.Coach;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 教练表 Mapper。
 *
 * <p>单表 CRUD 由 BaseMapper 提供；这里只写一条真正需要 join 的查询。
 * join 在 SQL 层做而不是"先查关联表 id 再循环查教练"，是因为后者会产生 N+1 次查询。</p>
 */
public interface CoachMapper extends BaseMapper<Coach> {

    /**
     * 查"这门课在这家店有哪些可约教练"。
     *
     * <p>三张表同在 yd_course 库，所以能直接 JOIN——这也是为什么教练实体要放在课程库：
     * 这个查询是预约页的第一条查询，跨库就得改成 Feign + 内存拼装。</p>
     *
     * <p>索引走向：{@code t_coach_course} 用 uk_coach_course 的最左前缀反查不合适，
     * 所以建表时专门加了 {@code idx_course(course_id)}；命中后再按
     * {@code t_coach} 主键回表取教练，最后用 {@code idx_store_status} 过滤门店与状态。</p>
     *
     * @param courseId 课程 id
     * @param storeId  门店 id
     * @return 可约教练列表（只含 status=1 的）
     */
    @Select("""
            SELECT c.*
              FROM t_coach c
              JOIN t_coach_course cc ON cc.coach_id = c.id
             WHERE cc.course_id = #{courseId}
               AND c.store_id = #{storeId}
               AND c.status = 1
             ORDER BY c.id
            """)
    List<Coach> selectByCourseAndStore(@Param("courseId") Long courseId, @Param("storeId") Long storeId);
}
