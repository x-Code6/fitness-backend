package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.CoachCourse;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 教练可授课范围 Mapper。
 *
 * <p>单表查询（按 coach_id 或 course_id 取关联行）直接用 BaseMapper 的
 * {@code selectList(LambdaQueryWrapper)} 在 Service 里写就行，不在这里重复定义方法，
 * 免得两套写法并存看不出该用哪个。这里只保留一条真正需要自定义的删除。</p>
 */
public interface CoachCourseMapper extends BaseMapper<CoachCourse> {

    /**
     * 清空某教练的全部配课关系。
     *
     * <p>供管理端"保存教练可授课范围"使用：采用<b>先删后插的整体替换</b>语义，
     * 不去计算"哪些要删哪些要加"——差异计算复杂、容易漏，而且配课量很小（一个教练几门课），
     * 全删重插的代价可以忽略。</p>
     *
     * @param coachId 教练 id
     * @return 删除的行数（本来就一条没配时为 0，不算异常）
     */
    @Delete("DELETE FROM t_coach_course WHERE coach_id = #{coachId}")
    int deleteByCoachId(@Param("coachId") Long coachId);
}
