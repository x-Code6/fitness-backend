package com.yuedong.order.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.EntitlementAdminDTO;
import com.yuedong.order.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 凭证（权益轴）内部接口，只服务管理端。
 *
 * <p>★和订单、预约各自一个 inner controller：这三条状态机轴在库上是三张表，
 * 接口按轴分组建，而不是"反正都是 order 服务就塞一个 controller"。
 * 将来某条轴要独立出去（比如凭证做成权益服务），搬的是一个文件而不是一次解剖。</p>
 */
@RestController
@RequestMapping("/inner/entitlement")
@RequiredArgsConstructor
public class InnerEntitlementController {

    private final EntitlementService entitlementService;

    /** 管理端：凭证分页 */
    @GetMapping("/admin/page")
    public Result<PageDTO<EntitlementAdminDTO>> adminPage(@RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) Long userId,
                                                          @RequestParam(defaultValue = "1") Integer page,
                                                          @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(entitlementService.adminPage(status, keyword, userId, page, size));
    }
}
