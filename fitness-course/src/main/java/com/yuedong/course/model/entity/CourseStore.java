package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程 × 门店（多对多关联 + 两层时间窗），对应表 yd_course.t_course_store。
 *
 * <p>这张表回答三个问题：这门课<b>在哪家店</b>卖、<b>什么时候能买</b>、
 * 买完之后<b>什么时候之前要用完</b>。三层信息里两层是时间，而且它们校验的动作完全不同，
 * 绝对不能合并成一列：</p>
 *
 * <table border="1">
 *   <caption>两层时间的分工</caption>
 *   <tr><th></th><th>校验时机</th><th>不满足时</th><th>落在哪条链路</th></tr>
 *   <tr><td>① 可购买 {@code sellStartTime/sellEndTime}</td><td>加购物车、创建订单前</td>
 *       <td>"该课程已停售"</td><td>购买链路</td></tr>
 *   <tr><td>② 可使用 {@code validType/validDays/useStartTime/useEndTime}</td>
 *       <td>只用来<b>算出</b> {@code t_entitlement.validUntil}，之后预约时校验</td>
 *       <td>"凭证已过期"</td><td>预约链路</td></tr>
 * </table>
 *
 * <p>★{@code validType=FOREVER} 会让凭证的 {@code validUntil} 存 null，
 * 于是所有过期判断都必须带 {@code IS NOT NULL}。漏掉的后果是常年课被集体误判过期退款，
 * 这类 bug 在测试里很难被发现，所以这里的注释和 {@code CoachService} 一样写得偏长。</p>
 */
@Data
@TableName("t_course_store")
public class CourseStore {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 课程 id（同库逻辑外键 → t_course.id，可直接 JOIN） */
    @TableField("course_id")
    private Long courseId;

    /** 门店 id（同库逻辑外键 → t_store.id） */
    @TableField("store_id")
    private Long storeId;

    /** ① 开售时间（含） */
    @TableField("sell_start_time")
    private LocalDateTime sellStartTime;

    /** ① 停售时间（含）；null = 长期在售（常年日常价课） */
    @TableField("sell_end_time")
    private LocalDateTime sellEndTime;

    /** ② FOREVER 永不过期 / DAYS 自支付时刻起 N 天 / RANGE 固定起止窗口 */
    @TableField("valid_type")
    private String validType;

    /** validType=DAYS 时生效：领后 N 天内可用 */
    @TableField("valid_days")
    private Integer validDays;

    /** validType=RANGE 时生效：可使用起始 */
    @TableField("use_start_time")
    private LocalDateTime useStartTime;

    /** validType=RANGE 时生效：可使用截止 */
    @TableField("use_end_time")
    private LocalDateTime useEndTime;

    /** 可售份数；null = 不限份。★旧库 capacity/booked 的"座位"语义已废弃，这里只管卖出多少份 */
    @TableField("stock")
    private Integer stock;

    /** 状态：1 在售 / 0 下架（同一门课在不同门店可以分别下架） */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
