package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.Course;
import org.apache.ibatis.annotations.Param;
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
}
