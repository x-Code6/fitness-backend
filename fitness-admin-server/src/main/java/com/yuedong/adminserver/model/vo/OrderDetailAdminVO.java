package com.yuedong.adminserver.model.vo;

import com.yuedong.order.api.dto.BookingDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 订单详情页的一整包数据：<b>三条轴</b> + 会员昵称补齐。
 *
 * <p>★为什么不直接透传 order 服务的 {@code OrderAdminDetailDTO}：
 * 那份 DTO 里两条列表是<b>平铺</b>的（凭证一张表、预约一张表，各自按自己的归属建模），
 * 而管理页要的是"每张凭证下面挂着它约过的课"。<b>把平铺的真相按界面重新挂一遍</b>
 * 正是 BFF 的活；同时昵称也只有在这一层才补得上（yd_user 在另一个库）。</p>
 *
 * <p>{@code reservations} 顶层仍保留一份平铺列表：详情页"全部预约"那个 tab 要用，
 * 前端不必自己把嵌套结构再拆平。</p>
 */
@Data
@Builder
public class OrderDetailAdminVO {

    /** 钱那一轴 */
    private BookingDTO order;

    /** 权益那一轴，每张下面挂着它约过的课 */
    private List<EntitlementAdminVO> entitlements;

    /** 时段那一轴，平铺按时间倒序 */
    private List<ReservationAdminVO> reservations;
}
