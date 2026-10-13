package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.Schedule;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 课程场次 Mapper：名额占用/释放按场次原子操作。
 */
public interface ScheduleMapper extends BaseMapper<Schedule> {

    /**
     * 原子占用本场名额：booked < capacity 才 +1，防并发超约。
     */
    @Update("UPDATE t_course_schedule SET booked = booked + 1 " +
            "WHERE id = #{scheduleId} AND status = 1 AND booked < capacity")
    int occupy(@Param("scheduleId") Long scheduleId);

    /**
     * 原子释放本场名额。
     */
    @Update("UPDATE t_course_schedule SET booked = booked - 1 " +
            "WHERE id = #{scheduleId} AND booked > 0")
    int release(@Param("scheduleId") Long scheduleId);
}
