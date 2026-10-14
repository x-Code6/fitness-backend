package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.Course;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 课程表 Mapper。
 *
 * <p>★v2 删掉了 {@code occupy} / {@code release} 两条"原子占名额"的 UPDATE
 * （它们依赖已废弃的 {@code capacity}/{@code booked} 列）。</p>
 *
 * <p>这不代表并发防护变弱了，而是<b>防护对象换了位置</b>：旧模型要防的是"20 座的课约进 21 人"，
 * 新模型要防的是"同一教练同一整点被两个人占"，后者由
 * {@code yd_order.t_coach_busy} 的 {@code uk_coach_start(coach_id, start_time)}
 * 唯一索引承担——比读-改-写更简单，也不需要跨服务补偿。</p>
 */
public interface CourseMapper extends BaseMapper<Course> {

    /**
     * 判断某课程 id 是否存在，只数行数、不取任何列。
     *
     * <p>为什么不用 {@code selectById()} 判空：那条语句会把 Course 实体的全部列都 SELECT 出来。
     * 校验"存在性"本身不需要任何列，写清楚比让框架去猜更稳，
     * 将来课程再加减列也不会连累这里。</p>
     *
     * @param courseId 课程 id
     * @return 1 存在 / 0 不存在
     */
    @Select("SELECT COUNT(1) FROM t_course WHERE id = #{courseId}")
    int countById(@Param("courseId") Long courseId);
}
