package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.CourseRatingStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 课程评分汇总 Mapper。原子增减的理由同 {@link StoreRatingStatMapper}。 */
public interface CourseRatingStatMapper extends BaseMapper<CourseRatingStat> {

    @Insert("INSERT INTO t_course_rating_stat (course_id, rating_sum, rating_count) " +
            "VALUES (#{targetId}, #{deltaSum}, #{deltaCount}) " +
            "ON DUPLICATE KEY UPDATE rating_sum = rating_sum + #{deltaSum}, " +
            "                        rating_count = GREATEST(rating_count + #{deltaCount}, 0)")
    int addScore(@Param("targetId") Long targetId,
                 @Param("deltaSum") Integer deltaSum,
                 @Param("deltaCount") Integer deltaCount);
}
