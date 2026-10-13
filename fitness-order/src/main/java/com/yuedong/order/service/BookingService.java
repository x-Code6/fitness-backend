package com.yuedong.order.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.vo.BookingVO;

import java.util.List;

/**
 * 预约订单业务接口。
 */
public interface BookingService {

    /**
     * 普通预约：查课程 → 同人同课去重 → 占名额 → 建 UNPAID 单 → 发延迟取消消息。
     */
    BookingVO create(Long userId, BookingCreateDTO dto);

    /** 我的预约列表（可选状态筛选） */
    List<BookingVO> list(Long userId, String status);

    /**
     * 模拟支付：UNPAID → BOOKED（真实项目替换为支付回调驱动状态变更）。
     */
    void pay(Long userId, String orderNo);

    /**
     * 主动取消：状态 → CANCELED 并释放名额。
     */
    void cancel(Long userId, String orderNo);

    /**
     * 核销（内部接口，管理端核销工作台调用）：核销码 + BOOKED → FINISHED。
     *
     * @return 核销成功的订单信息（含 userId，供工作台展示会员昵称）
     */
    BookingDTO verifyByCode(String verifyCode);

    // =====================================================================
    // 管理端（fitness-admin-server 经 Feign 调用）
    // =====================================================================

    /** 订单分页（全用户；keyword 匹配订单号） */
    PageDTO<BookingDTO> adminPage(String status, String keyword, Integer page, Integer size);

    /** 管理端代客取消：UNPAID/BOOKED → CANCELED（释放名额 + 退券 + 通知） */
    void adminCancel(String orderNo);

    /** 最近核销的订单（核销工作台记录） */
    List<BookingDTO> verifiedRecent();
}
