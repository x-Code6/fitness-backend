package com.yuedong.order.service;

import com.yuedong.order.model.dto.CartAddDTO;
import com.yuedong.order.model.dto.CartItemUpdateDTO;
import com.yuedong.order.model.dto.CheckoutDTO;
import com.yuedong.order.model.vo.CartItemVO;
import com.yuedong.order.model.vo.CartListVO;
import com.yuedong.order.model.vo.CheckoutResultVO;

/**
 * 购物车业务接口（D12）。
 *
 * <p>★<b>购物车存 MySQL 不存 Redis</b>，理由在 {@code CartItem} 的类注释里：
 * 要带勾选状态、要跨设备、要和结算页来回跳。
 * 餐饮类"会话级购物车"用 Redis 合理，本项目不合理——
 * <b>同一个技术选择在不同数据形态下结论是相反的，这才是真正要理解的点。</b></p>
 */
public interface CartService {

    /** 加购：同课同店已有条目则累加份数（不插新行，靠 uk_user_course_store） */
    CartItemVO add(Long userId, CartAddDTO dto);

    /** 改份数或勾选状态（传了才改） */
    void update(Long userId, CartItemUpdateDTO dto);

    /** 删除条目 */
    void remove(Long userId, Long itemId);

    /** 购物车整页：条目 + 合计 + 已选数量 */
    CartListVO list(Long userId);

    /**
     * 结算：按勾选条目拆成 N 张订单，共用一个 {@code parentNo}（D12）。
     * <p>★<b>券按子单各选一张</b>（已确认口径），所以入参是每条带一张券。</p>
     */
    CheckoutResultVO checkout(Long userId, CheckoutDTO dto);
}
