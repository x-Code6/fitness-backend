package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.CartItem;

/**
 * 购物车 Mapper。
 *
 * <p>全部操作走 MyBatis-Plus 的通用条件即可，不需要自定义 SQL——
 * 购物车是低并发、单用户自己操作的数据，
 * 三档标准里属于"不防"：<b>不是所有表都值得写一段原子 UPDATE</b>。</p>
 */
public interface CartItemMapper extends BaseMapper<CartItem> {
}
