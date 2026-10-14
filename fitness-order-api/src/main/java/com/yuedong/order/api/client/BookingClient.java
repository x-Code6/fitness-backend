package com.yuedong.order.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.BookingDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务内部契约（供 fitness-admin-server 调用）。
 *
 * <p>★v2 删掉 {@code verify} 与 {@code verifiedRecent} 两个方法：
 * 核销的对象是<b>预约</b>（教练确认某人上过课），不是订单，
 * 它们随 {@code t_reservation} 一起在预约分支里重新出现。
 * 留在订单上就是把三条状态机轴塞回一张表——那正是这次重构要拆开东西。</p>
 *
 * <p>管理端"核销工作台"页面因此暂时下线，菜单项同步去掉，
 * 避免出现一个点开就 404 的入口。</p>
 */
@FeignClient(name = "fitness-order")
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
}
