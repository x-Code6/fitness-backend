package com.yuedong.order.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.vo.BookingDetailVO;
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
     * @param dto    课程 + 门店 + 份数 + 可用券
     * @return 订单视图，含支付所需的 actions
     */
    BookingVO create(Long userId, BookingCreateDTO dto);

    /**
     * 建单并归入一个组号（<b>只给购物车结算用</b>）。
     *
     * <p>★为什么不把 {@code parentNo} 放进 {@link BookingCreateDTO} 让前端传：
     * 组号是"这次结算"的产物，不是用户的输入。让它出现在对外入参里，
     * 就等于允许客户端把一张新单挂到任意旧组上去——整组支付会连着一张他没打算付的单一起流转。
     * <b>服务端生成的标识不出现在入参 DTO 里，这是一条该守的边界。</b></p>
     */
    BookingVO create(Long userId, BookingCreateDTO dto, String parentNo);

    /**
     * 我的订单列表。
     *
     * <p>★参数叫 {@code tab} 而不是 {@code status} 是有原因的：
     * 前五个 tab 能直接落到订单轴上，但"待使用/已预约/待评价/已过期"
     * <b>跨三条轴</b>，必须由凭证与预约的数据参与筛。
     * 取值：ALL / UNPAID / PAID / CANCELED / REFUNDED /
     * WAIT_USE / BOOKED / FINISHED / TO_RATE / EXPIRED。</p>
     */
    List<BookingVO> list(Long userId, String tab);

    /** 订单详情（嵌凭证与其下的预约记录） */
    BookingDetailVO detail(Long userId, String orderNo);

    /**
     * 模拟支付：CAS 流转 UNPAID → PAID，并<b>在同一事务里出票</b>。
     *
     * <p>★{@code orderNo} 与 {@code parentNo} 至少传一个（前端二选一发的）：
     * 传 parentNo 时同组订单一次付清并逐单出票。
     * 用一条 {@code UPDATE ... WHERE parent_no=?} 而不是循环调单笔支付，
     * 是为了避免"付了 3 张里的 2 张"。</p>
     */
    void pay(Long userId, String orderNo, String parentNo);

    /** 主动取消：只有未支付可取消；传 parentNo 时整组取消 */
    void cancel(Long userId, String orderNo, String parentNo);

    // =====================================================================
    // 管理端（fitness-admin-server 经 Feign 调用）
    // =====================================================================

    /** 管理端：订单分页 */
    PageDTO<BookingDTO> adminPage(String status, String keyword, Integer page, Integer size);

    /** 管理端：代客取消未支付订单 */
    void adminCancel(String orderNo);
}
