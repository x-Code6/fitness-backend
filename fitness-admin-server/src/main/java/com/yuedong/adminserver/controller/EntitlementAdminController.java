package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.service.AdminVoConverter;
import com.yuedong.adminserver.service.MemberNameLoader;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.adminserver.model.vo.EntitlementAdminVO;
import com.yuedong.order.api.client.BookingClient;
import com.yuedong.order.api.dto.EntitlementAdminDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 凭证管理（权益轴）。
 *
 * <p>★这一页是 v2 三条状态机拆开后才存在的界面：
 * 旧模型里"还剩几次"要去订单表猜（购买和上课挤在同一张表），
 * 现在凭证自己是一张表、有自己的状态，管理端于是能直接问"哪些凭证快过期了"。</p>
 */
@RestController
@RequestMapping("/admin/entitlement")
@RequiredArgsConstructor
public class EntitlementAdminController {

    private final BookingClient orderClient;
    private final MemberNameLoader memberNameLoader;
    private final AdminVoConverter converter;

    /**
     * 凭证分页。
     *
     * @param status  ACTIVE / USED_UP / EXPIRED，不传或 ALL 表示全部
     * @param keyword 订单号 / 课程名 / 门店名 模糊。<b>昵称不参与筛选</b>：
     *                它在 yd_user，跨库 LIKE 不了；要按人查就从会员页点进来带 userId 参数
     * @param userId  指定会员
     */
    @GetMapping("/page")
    public Result<PageDTO<EntitlementAdminVO>> page(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Long userId,
                                                    @RequestParam(defaultValue = "1") Integer page,
                                                    @RequestParam(defaultValue = "10") Integer size) {
        PageDTO<EntitlementAdminDTO> data =
                orderClient.entitlementPage(status, keyword, userId, page, size).getData();
        if (data == null || data.getList().isEmpty()) {
            return Result.ok(PageDTO.of(List.of(), data == null ? 0 : data.getTotal()));
        }

        Map<Long, String> names = memberNameLoader.mapOf(data.getList().stream()
                .map(EntitlementAdminDTO::getUserId).toList());
        List<EntitlementAdminVO> rows = data.getList().stream()
                .map(row -> converter.toVO(row, names))
                .toList();
        return Result.ok(PageDTO.of(rows, data.getTotal()));
    }
}
