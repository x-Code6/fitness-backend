package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 券-可用课程（{@code scope=1} 时生效），对应表 yd_course.t_coupon_course。
 *
 * <p>★为什么不把课程 id 存成逗号串放在 t_coupon 上：判断"这张券能不能用在某门课上"
 * 要变成字符串 LIKE，既用不上索引又会误匹配（"1" 匹配到 "11"）。
 * 关联表 + 唯一键既是正确写法也是天然约束。</p>
 */
@Data
@TableName("t_coupon_course")
public class CouponCourse {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("coupon_id")
    private Long couponId;

    @TableField("course_id")
    private Long courseId;
}
