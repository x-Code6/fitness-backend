package com.yuedong.order.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.AdminStatDTO;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.api.dto.EntitlementAdminDTO;
import com.yuedong.order.api.dto.OrderAdminDetailDTO;
import com.yuedong.order.api.dto.ReservationAdminDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务内部契约（供 fitness-admin-server 调用）：订单 / 凭证 / 预约三条轴的管理端读口。
 *
 * <p>★v2 删掉 {@code verify} 与 {@code verifiedRecent} 两个方法：
 * 核销的对象是<b>预约</b>（教练确认某人上过课），不是订单，
 * 它们随 {@code t_reservation} 一起回到下面的预约那一段里——但入口路径已经变了。
 * 留在订单上就是把三条状态机轴塞回一张表，那正是这次重构要拆开的东西。</p>
 *
 * <p>★<b>为什么必须写 {@code contextId}（第一次真实启动就炸在这里）</b>：
 * Feign 给每个客户端注册一个 {@code <name>.FeignClientSpecification} Bean，
 * 键取的是 <b>name 而不是接口名</b>。本项目的契约模块里同一个服务有多个客户端
 * （course 有 Course/CourseAdmin/Store/Coupon/Coach 五个，order 有 Booking/Rating 两个），
 * 不配 contextId 就是"同一个 Bean 名注册两次" →
 * 启动直接失败：{@code The bean 'fitness-course.FeignClientSpecification' could not be registered}。
 * ⚠️ 报错提示的 {@code spring.main.allow-bean-definition-overriding=true} <b>不要用</b>：
 * 它只是让后注册的覆盖先注册的，表面能起来了，实际剩一个客户端、另一批方法全不存在，
 * 变成运行期才炸的更难的 bug。</p>
 */
@FeignClient(name = "fitness-order", contextId = "bookingClient")
public interface BookingClient {

    /** 管理端：订单分页 */
    @GetMapping("/inner/booking/admin/page")
    Result<PageDTO<BookingDTO>> orderPage(@RequestParam("status") String status,
                                          @RequestParam("keyword") String keyword,
                                          @RequestParam("page") Integer page,
                                          @RequestParam("size") Integer size);

    /** 管理端：代客取消未支付订单 */
    @PostMapping("/inner/booking/admin/cancel")
    Result<Void> adminCancel(@RequestParam("orderNo") String orderNo);

    /** 管理端：订单详情（订单 + 该单的凭证列表 + 该单的预约列表） */
    @GetMapping("/inner/booking/admin/detail")
    Result<OrderAdminDetailDTO> orderDetail(@RequestParam("orderNo") String orderNo);

    /** 管理端：看板统计（订单侧那一半，课程/user 各自补另一半） */
    @GetMapping("/inner/booking/admin/stat")
    Result<AdminStatDTO> adminStat();

    // ---------- 凭证（权益轴）----------

    /**
     * 管理端：凭证分页。
     *
     * @param status  ACTIVE / USED_UP / EXPIRED，null 或 ALL 表示全部
     * @param keyword 模糊匹配订单号与课程名（昵称的匹配在 admin-server 侧做不了跨库，故不含）
     * @param userId  指定会员（从会员列表点进来时用）
     */
    @GetMapping("/inner/entitlement/admin/page")
    Result<PageDTO<EntitlementAdminDTO>> entitlementPage(@RequestParam("status") String status,
                                                         @RequestParam("keyword") String keyword,
                                                         @RequestParam("userId") Long userId,
                                                         @RequestParam("page") Integer page,
                                                         @RequestParam("size") Integer size);

    // ---------- 预约（时段轴）----------

    /**
     * 管理端：预约分页。
     *
     * @param status  BOOKED / FINISHED / CANCELED
     * @param coachId 按教练筛（教练详情页要看他这一段的课）
     * @param storeId 按门店筛
     * @param date    某一天，格式 yyyy-MM-dd；★按 {@code start_time} 落在当天判
     * @param keyword 模糊匹配预约单号、订单号、课程名、教练名
     */
    @GetMapping("/inner/reservation/admin/page")
    Result<PageDTO<ReservationAdminDTO>> reservationPage(@RequestParam("status") String status,
                                                        @RequestParam("coachId") Long coachId,
                                                        @RequestParam("storeId") Long storeId,
                                                        @RequestParam("date") String date,
                                                        @RequestParam("keyword") String keyword,
                                                        @RequestParam("page") Integer page,
                                                        @RequestParam("size") Integer size);

    /**
     * 管理端：到店核销（只登记到店，不扣次——扣次在到点任务里，D5）。
     * <p>按核销码找单：前台店员手里只有用户出示的那串数字，没有预约单号。</p>
     */
    @PostMapping("/inner/reservation/admin/verify")
    Result<ReservationAdminDTO> reservationVerify(@RequestParam("verifyCode") String verifyCode);

    /**
     * 管理端：某教练是否还有<b>未开始</b>的预约（用于删教练前的前置判断）。
     *
     * <p>★这个判断必须在 order 侧做：{@code t_reservation} 在那边。
     * 由 admin-server 先调这里、通过后再调 course 的删除接口，
     * 而不是让 course 反向依赖 order——两个服务互相依赖就成环了。</p>
     */
    @GetMapping("/inner/reservation/admin/coach-has-future")
    Result<Boolean> coachHasFuture(@RequestParam("coachId") Long coachId);
}
