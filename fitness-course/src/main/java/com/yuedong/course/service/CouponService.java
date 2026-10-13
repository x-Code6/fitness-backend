package com.yuedong.course.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.model.vo.CouponVO;
import com.yuedong.course.model.vo.MyCouponVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 优惠券业务接口（领券复用秒杀的"Lua 原子扣减"框架）。
 */
public interface CouponService {

    /** 领券中心：上架中且未过期的券列表（标记当前用户是否已领） */
    List<CouponVO> listCenter(Long userId);

    /** 我的券（持券记录 + 券信息，标记是否可用） */
    List<MyCouponVO> myCoupons(Long userId);

    /**
     * 领券：Lua 原子校验"库存 + 一人一张"并扣减 → 写持券记录 → 扣 DB 库存。
     */
    void receive(Long userId, Long couponId);

    /** 内部接口：校验持券可用性（订单服务下单前调用，amount=订单原价） */
    CouponUsableDTO usableInfo(Long recordId, Long userId, BigDecimal amount);

    /** 内部接口：核销用券（UNUSED → USED，绑定订单号，CAS） */
    boolean use(Long recordId, Long userId, String orderNo);

    /** 内部接口：订单取消/超时后退券（USED → UNUSED） */
    boolean refund(Long recordId);

    /** 管理端：券分页 */
    PageDTO<CouponAdminDTO> adminPage(Integer page, Integer size);

    /** 管理端：创建/编辑券（创建时 remain=total，库存预热进 Redis） */
    void adminSave(CouponSaveDTO dto);

    /** 管理端：作废券 */
    void adminEnd(Long id);
}
