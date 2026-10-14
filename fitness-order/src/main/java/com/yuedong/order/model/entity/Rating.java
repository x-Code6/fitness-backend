package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单评价实体，对应表 yd_order.t_rating。
 *
 * <p>★<b>为什么放在 yd_order 而不是 yd_course</b>：写评价要校验"这单已支付、凭证已用完、
 * 这单没评过"，数据全在本库 → 零跨服务；三张评分汇总统计也在本库，
 * 与明细<b>同事务</b>更新才安全。放课程库就变成"改课程库的汇总列 + 校验订单库的支付状态"，
 * 那才真需要分布式事务——本项目明确不引入。</p>
 *
 * <p>★<b>软删而不是真删</b>是"可删不可改"的实现关键：
 * 如果真 DELETE 掉这一行，{@code uk_order} 就不拦了，用户删掉再评一次等于变相修改评分。
 * 保留行、唯一键继续生效，才做得到"能删一次，删完不能重评"。</p>
 */
@Data
@TableName("t_rating")
public class Rating {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** ★一单一评（唯一索引 uk_order 兜并发双击） */
    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Long userId;

    @TableField("course_id")
    private Long courseId;

    @TableField("store_id")
    private Long storeId;

    /** 评价时顺手选的一位教练，可空。★一单约过多个教练时只计这一位 */
    @TableField("coach_id")
    private Long coachId;

    @TableField("coach_name")
    private String coachName;

    /** 1~5 星。入参用 @Min/@Max 拦，不给 0 或 7 进来的机会 */
    @TableField("score")
    private Integer score;

    @TableField("comment")
    private String comment;

    /** 1 正常 / 0 已删除（逻辑删除）。★见类注释 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
