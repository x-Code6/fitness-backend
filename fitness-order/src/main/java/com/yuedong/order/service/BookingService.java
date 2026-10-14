package com.yuedong.order.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.vo.BookingVO;

import java.util.List;

/**
 * 订单业务接口（订单轴 = 钱）。
 *
 * <p>★v2 删掉的方法：{@code verifyByCode} 与 {@code verifiedRecent}。
 * 核销的对象是<b>预约</b>（教练确认"这个人来上过课了"），不是订单，
 * 所以它们随 {@code t_reservation} 一起回到预约分支里去实现。
 * 留在订单上就会重复我文档里批评过的那件事：把三条状态机轴塞进一张表、一个字段。</p>
 *
 * <p>也没有 {@code releaseSlot} 之类的名额方法：购买不再占名额，
 * 时段占用发生在创建预约时，由 {@code t_coach_busy} 的唯一索引承担。</p>
 */
public interface BookingService {

    /**
     * 创建购买订单（不占名额，所以<b>不需要任何跨服务补偿</b>）。
     *
     * @param userId 下单人，只从 UserContext 取，绝不接受前端传参
     * @param dto    课程 + 门店 + 可用券
     * @return 订单视图，含支付所需的 actions
     */
    BookingVO create(Long userId, BookingCreateDTO dto);

    /** 我的订单列表，status 为空或 ALL 表示全部 */
    List<BookingVO> list(Long userId, String status);

    /** 模拟支付：CAS 流转 UNPAID → PAID。同组订单（购物车结算）一起支付 */
    void pay(Long userId, String orderNo);

    /** 主动取消：只有未支付可取消 */
    void cancel(Long userId, String orderNo);

    // =====================================================================
    // 管理端（fitness-admin-server 经 Feign 调用）
    // =====================================================================

    /** 管理端：订单分页 */
    PageDTO<BookingDTO> adminPage(String status, String keyword, Integer page, Integer size);

    /** 管理端：代客取消未支付订单 */
    void adminCancel(String orderNo);
}
