package com.yuedong.order.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 管理端订单详情：一单的<b>三条轴</b>一次给全。
 *
 * <p>★这个类的形状就是 v2 重构的结论本身：
 * 订单（钱）、凭证（权益）、预约（时段）是三张表三条状态机，
 * 详情页要同时回答"付没付、还剩几次、上到哪一节"。
 * 旧模型把它们塞进一张 {@code t_booking}，于是"已过期"既像订单状态又像课程状态，
 * 谁也说不清——现在分成三段，每段一个列表，读代码的人不用猜。</p>
 *
 * <p>三个列表<b>都在同一库</b>，所以本服务一次查完而不是让 admin-server 发三次 Feign。</p>
 */
@Data
@Builder
public class OrderAdminDetailDTO {

    /** 钱那一轴：订单本体 */
    private BookingDTO order;

    /** 权益那一轴：这一单出了几张凭证、各用了多少 */
    private List<EntitlementAdminDTO> entitlements;

    /** 时段那一轴：这一单约出来的预约单 */
    private List<ReservationAdminDTO> reservations;
}
