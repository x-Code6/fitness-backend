package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.adminserver.model.vo.OrderAdminVO;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.client.BookingClient;
import com.yuedong.order.api.dto.BookingDTO;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 预约订单管理：分页 + 代客取消。
 * 页面数据 = 订单（order 服务）+ 会员昵称（user 服务 Feign 批量补齐）。
 */
@Slf4j
@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class OrderAdminController {

    private final BookingClient orderClient;
    private final UserClient userClient;

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

        // ===== 第 2 步：Feign 批量补齐会员昵称（order 表只存 userId；失败降级为 null） =====
        Map<Long, String> nameMap = fetchUserNames(data.getList());
        List<OrderAdminVO> rows = data.getList().stream()
                .map(b -> OrderAdminVO.builder()
                        .id(b.getId())
                        .userName(nameMap.get(b.getUserId()))
                        .courseName(b.getCourseName())
                        .coach(b.getCoach())
                        .storeName(b.getStoreName())
                        .price(b.getPrice())
                        .status(b.getStatus())
                        .createdAt(b.getCreatedAt() == null ? null : b.getCreatedAt().toString())
                        .build())
                .toList();
        return Result.ok(PageDTO.of(rows, data.getTotal()));
    }

    @OperateLog(module = "预约订单", operation = "代客取消订单")
    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestParam String orderNo) {
        return orderClient.adminCancel(orderNo);
    }

    /** 批量取用户昵称并按 id 建映射；user 服务不可用时返回空映射（昵称列显示空） */
    private Map<Long, String> fetchUserNames(List<BookingDTO> bookings) {
        try {
            List<Long> userIds = bookings.stream()
                    .map(BookingDTO::getUserId).distinct().toList();
            var users = userClient.batch(new BatchUserQueryDTO(userIds));
            if (users.getCode() == 0 && users.getData() != null) {
                return users.getData().stream()
                        .collect(Collectors.toMap(UserBriefDTO::getId, UserBriefDTO::getNickname));
            }
        } catch (Exception e) {
            log.warn("会员昵称补齐失败，降级显示空", e);
        }
        return Map.of();
    }
}
