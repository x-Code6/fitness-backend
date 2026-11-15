package com.yuedong.order.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.api.dto.OrderAdminDetailDTO;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.vo.BookingDetailVO;
import com.yuedong.order.model.vo.BookingPreviewVO;
import com.yuedong.order.model.vo.BookingVO;


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
     * 下单页试算：算出金额，并给出这个份数下用户手里哪些券可用。<b>只读，不写库、不核销券。</b>
     *
     * <p>★用户每改一次份数、每换一张券都会打这一个接口。之所以不放过前端自己算：
     * "这张券能不能用"有五个条件（金额门槛、券的使用时间窗口、课程白名单、门店白名单、
     * 已使用状态），这些规则只允许在课程服务里有一份。前端复制一份的结果一定是
     * "列表里显示可用、点提交报错 COUPON_NOT_USABLE"。</p>
     *
     * <p>★金额与 {@code create} 用的是同一个算价方法
     * （{@link com.yuedong.order.service.support.BookingPriceCalculator}），所以用户在页面上看到的实付价
     * 和最终建单的成交价不会是两个数。</p>
     *
     * @param quantity       份数，null 按 1；超过上限直接报错（不静默截断）
     * @param couponRecordId 选中的券，null = 不用券
     */
    /**
     * 下单页试算。
     *
     * @param activityId 秒杀活动 id；<b>传了就是秒杀态</b>——份数锁 1、不能用券、价格取活动价。
     *                   普通购买传 null。★试算只读，不扣任何库存。
     */
    BookingPreviewVO preview(Long userId, Long courseId, Long storeId, Integer quantity,
                             Long couponRecordId, Long activityId);

    /**
     * 我的订单列表（分页）。
     *
     * <p>★参数叫 {@code tab} 而不是 {@code status} 是有原因的：
     * 前五个 tab 能直接落到订单轴上，但"待使用/已预约/待评价/已过期"
     * <b>跨三条轴</b>，必须由凭证与预约的数据参与筛。
     * 取值：ALL / UNPAID / PAID / CANCELED / REFUNDED /
     * WAIT_USE / BOOKED / FINISHED / TO_RATE / EXPIRED。</p>
     *
     * <p>★简单 tab 直接在数据库分页；跨轴 tab 先取该用户的候选订单，
     * 在内存里按三轴过滤后再切页。后者的 total 是过滤后的真实条数。</p>
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，服务端上限 50
     */
    PageDTO<BookingVO> list(Long userId, String tab, Integer page, Integer size);

    /** 订单详情（嵌凭证与其下的预约记录） */
    BookingDetailVO detail(Long userId, String orderNo);

    /**
     * 模拟支付：CAS 流转 UNPAID → PAID，并<b>在同一事务里出票</b>。
     *
     * <p>★"同事务"是本方法的全部并发方案：订单状态与凭证同在 yd_order 一个库，
     * 一次提交要么都成、要么都不成，不会出现"钱扣了没票"或"有票没扣钱"。</p>
     */
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

    /**
     * 管理端：订单详情，<b>三条轴一次给全</b>（订单 + 该单凭证 + 该单预约）。
     *
     * <p>★三张表都在 yd_order，所以由本服务一次查完，而不是让 admin-server 发三次 Feign。
     * BFF 的聚合应该聚"跨服务才拿到的东西"（昵称、门店名），
     * 同一个库里的三张表让远端去拼，等于把数据归属又打散了。</p>
     */
    OrderAdminDetailDTO adminDetail(String orderNo);
}
