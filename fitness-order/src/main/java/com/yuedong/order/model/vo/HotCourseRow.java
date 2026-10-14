package com.yuedong.order.model.vo;

import lombok.Data;

/**
 * 热销课一行（内部读结果，<b>不给前端</b>，出去前转成 AdminStatDTO.HotCourse）。
 *
 * <p>★为什么不直接用 AdminStatDTO.HotCourse 接 SQL：
 * 对外契约的类一旦被 mapper 当结果类型用，字段就得跟着 SQL 的别名走，
 * 将来加一个"只给内部用的排序字段"就会顺手暴露给前端。
 * 中间放一个三行的小类，两边各自演化。</p>
 */
@Data
public class HotCourseRow {

    /** 课程名（订单表上的快照列） */
    private String name;

    /** 卖出份数 = SUM(quantity) */
    private Integer sold;
}
