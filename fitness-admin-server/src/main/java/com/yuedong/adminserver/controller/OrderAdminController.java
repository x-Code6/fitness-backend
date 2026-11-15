package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.adminserver.model.vo.EntitlementAdminVO;
import com.yuedong.adminserver.model.vo.OrderAdminVO;
import com.yuedong.adminserver.model.vo.OrderDetailAdminVO;
import com.yuedong.adminserver.model.vo.ReservationAdminVO;
import com.yuedong.adminserver.converter.AdminVoConverter;
import com.yuedong.adminserver.support.MemberNameLoader;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.client.BookingClient;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.order.api.dto.EntitlementAdminDTO;
import com.yuedong.order.api.dto.OrderAdminDetailDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单管理：分页、详情、代客取消。
 *
 * <p>页面数据 = 订单（order 服务）+ 会员昵称（user 服务）——
 * 这正是 BFF 存在的理由：订单服务只存 userId，"哪个会员下的单"要跨服务补。</p>
 *
 * <p>★详情页的<b>形状</b>由这一层决定：order 服务给的是"订单 + 一串凭证 + 一串预约"，
 * 而管理页面看的是"每张凭证下面挂着它约过的课"。
 * 归组这件事放在 BFF 而不是塞进订单服务的 DTO，是因为它是<b>展示结构</b>而不是数据关系；
 * 数据关系（reservation.entitlement_id）已经在字段里了，谁都能归。</p>
 */
@Slf4j
@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class OrderAdminController {

    private final BookingClient orderClient;
    private final MemberNameLoader memberNameLoader;
    private final AdminVoConverter converter;

    @GetMapping("/page")
    public Result<PageDTO<OrderAdminVO>> page(@RequestParam(defaultValue = "ALL") String status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") Integer page,
                                              @RequestParam(defaultValue = "10") Integer size) {
        // ===== 第 1 步：取订单分页 =====
        PageDTO<BookingDTO> data = orderClient.orderPage(status, keyword, page, size).getData();
        if (data == null || data.getList().isEmpty()) {
            return Result.ok(PageDTO.of(List.of(), data == null ? 0 : data.getTotal()));
        }

        // ===== 第 2 步：Feign 批量补齐会员昵称（一次，★不在循环里查）=====
        Map<Long, String> names = memberNameLoader.mapOf(data.getList().stream()
                .map(BookingDTO::getUserId).toList());

        List<OrderAdminVO> rows = data.getList().stream()
                .map(b -> OrderAdminVO.builder()
                        .orderNo(b.getOrderNo())
                        .nickname(names.get(b.getUserId()))
                        .userId(b.getUserId())
                        .courseName(b.getCourseName())
                        .storeName(b.getStoreName())
                        .lessonHours(b.getLessonHours())
                        .saleType(b.getSaleType())
                        .timesGranted(b.getTimesGranted())
                        .quantity(b.getQuantity())
                        .originalAmount(b.getOriginalAmount())
                        .discountAmount(b.getDiscountAmount())
                        .payAmount(b.getPayAmount())
                        .refundAmount(b.getRefundAmount())
                        .couponTitle(b.getCouponTitle())
                        .status(b.getStatus())
                        .statusText(b.getStatusText())
                        .fromSeckill(b.getFromSeckill())
                        .createdAt(b.getCreatedAt() == null ? null : b.getCreatedAt().toString())
                        .payTime(b.getPayTime() == null ? null : b.getPayTime().toString())
                        .build())
                .toList();
        return Result.ok(PageDTO.of(rows, data.getTotal()));
    }

    /**
     * 订单详情：三条轴一次给全，预约按所属凭证归组后挂在凭证下面。
     *
     * <p>★昵称只用一次批量查：把订单、凭证、预约三处出现过的 userId 收齐再查，
     * 分三次查就是三次 Feign 往返。</p>
     */
    @GetMapping("/detail")
    public Result<OrderDetailAdminVO> detail(@RequestParam String orderNo) {
        OrderAdminDetailDTO data = orderClient.orderDetail(orderNo).getData();
        if (data == null || data.getOrder() == null) {
            return Result.ok(null);
        }

        List<Long> userIds = new ArrayList<>();
        userIds.add(data.getOrder().getUserId());
        data.getEntitlements().forEach(e -> userIds.add(e.getUserId()));
        data.getReservations().forEach(r -> userIds.add(r.getUserId()));
        Map<Long, String> names = memberNameLoader.mapOf(userIds);

        // ===== 预约先按 entitlementId 分好组，再贴到各张凭证下面 =====
        List<ReservationAdminVO> flat = data.getReservations().stream()
                .map(row -> converter.toVO(row, names)).toList();
        Map<Long, List<ReservationAdminVO>> byEntitlement = new HashMap<>();
        for (ReservationAdminVO row : flat) {
            byEntitlement.computeIfAbsent(row.getEntitlementId(), k -> new ArrayList<>()).add(row);
        }

        List<EntitlementAdminVO> entitlements = new ArrayList<>();
        for (EntitlementAdminDTO row : data.getEntitlements()) {
            EntitlementAdminVO vo = converter.toVO(row, names);
            vo.setReservations(byEntitlement.getOrDefault(vo.getId(), List.of()));
            entitlements.add(vo);
        }

        return Result.ok(OrderDetailAdminVO.builder()
                .order(data.getOrder())
                .entitlements(entitlements)
                .reservations(flat)
                .build());
    }

    @OperateLog(module = "预约订单", operation = "代客取消订单")
    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestParam String orderNo) {
        return orderClient.adminCancel(orderNo);
    }
}
