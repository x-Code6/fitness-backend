package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车条目实体，对应表 yd_order.t_cart_item。
 *
 * <p>★<b>为什么存 MySQL 而不是 Redis</b>（这是"缓存该不该当数据库"那道题的一次正面回答）：
 * 购物车要带<b>勾选状态</b>、要跨设备、要和订单确认页来回跳。
 * 放 Redis 的话一次宕机用户挑好的东西就没了，而且没有约束可以依赖；
 * 放 MySQL 才能用 {@code uk_user_course_store} 保证"同课同店只有一条"，
 * 也让"改份数""勾选"这种操作是普通事务。
 * 餐饮类会话级购物车用 Redis 合理，本项目不合理。</p>
 */
@Data
@TableName("t_cart_item")
public class CartItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 谁的车。★所有查询必带，否则就成了"猜到条目 id 就能改别人的车" */
    @TableField("user_id")
    private Long userId;

    @TableField("course_id")
    private Long courseId;

    /**
     * ★认店：同一门课在两家店卖是两个独立条目。
     * 因为凭证只能在购买门店约教练，"哪门课"本身不足以确定能约谁。
     */
    @TableField("store_id")
    private Long storeId;

    /** 份数，1 份 = 1 张凭证（★凭证不合并，所以份数直接决定出票张数） */
    @TableField("quantity")
    private Integer quantity;

    /** 1 勾选结算 / 0 未勾选。★这个状态要持久：刷新页面还应该在，不然用户要重新勾一遍 */
    @TableField("selected")
    private Integer selected;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
