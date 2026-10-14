package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.Course;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 课程表 Mapper。
 *
 * <p>单表 CRUD 由 BaseMapper 提供；名额占用/释放是并发写，
 * 必须用"带条件的原子 UPDATE"（注解 SQL），返回影响行数判断成功与否。</p>
 */
public interface CourseMapper extends BaseMapper<Course> {

    /**
     * 原子占用名额：只有 booked < capacity 时才会 +1，天然防止超约。
     *
     * @return 影响行数：1 成功占用；0 名额已满
     */
    @Update("UPDATE t_course SET booked = booked + 1, updated_at = NOW() " +
            "WHERE id = #{courseId} AND status = 1 AND booked < capacity")
    int occupy(@Param("courseId") Long courseId);

    /**
     * 原子释放名额：booked > 0 才减，防止被取消请求减成负数。
     */
    @Update("UPDATE t_course SET booked = booked - 1, updated_at = NOW() " +
            "WHERE id = #{courseId} AND booked > 0")
    int release(@Param("courseId") Long courseId);

    /**
     * 判断某课程 id 是否存在（只数行数，不取任何列）。
     *
     * <p>为什么不用 {@code selectById(courseId)} 判空：那条语句会把 Course 实体的所有列
     * 都 SELECT 出来，而 v2 表结构正在改造（capacity/booked/coach 等列将不存在），
     * 校验"存在性"这件事本身不需要任何列，写清楚比让框架去猜更稳。</p>
     *
     * <p>等课程域对齐完成后，这个方法会被"批量查存在的课程 id"替换掉；
     * 现在配课是一次管理动作（一个教练几门课），循环几条 COUNT 不构成性能问题。</p>
     */
    @Select("SELECT COUNT(1) FROM t_course WHERE id = #{courseId}")
    int countById(@Param("courseId") Long courseId);
}
