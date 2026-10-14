package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.CouponCourse;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/** 券-可用课程 Mapper。★只用两个动作：按券删、按券批量插（整组覆盖） */
public interface CouponCourseMapper extends BaseMapper<CouponCourse> {

    /**
     * 删掉某券的全部课程范围。
     * <p>★管理端保存范围用"先全删再全插"而不是 diff 增删：一张券挂十几门课，
     * diff 要写三段代码还容易漏；全删全插是一条 DELETE + 一次批量 INSERT，
     * 而且<b>结果状态一定等于表单提交的内容</b>，不会有残留。
     * 这是"配置类数据"通用的写法，不适用于业务流水。</p>
     */
    @Delete("DELETE FROM t_coupon_course WHERE coupon_id = #{couponId}")
    int deleteByCoupon(@Param("couponId") Long couponId);
}
