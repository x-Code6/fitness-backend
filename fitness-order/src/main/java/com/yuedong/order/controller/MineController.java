package com.yuedong.order.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.vo.MineSummaryVO;
import com.yuedong.order.service.MineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "我的"页面聚合接口（需登录）。
 *
 * <p>网关路由：{@code /api/mine/**} → 本服务 {@code /mine/**}。</p>
 *
 * <p>★为什么这个接口在 order 服务而不是 user 服务：四宫格数的是订单、凭证、预约、
 * 待评价四件事，<b>全在 yd_order 一个库里</b>。放 user 服务就要跨三个服务各问一次，
 * 四个数字还会各自延迟到达。接口跟着数据走，不跟着"看起来像我的页面"走。</p>
 */
@RestController
@RequestMapping("/mine")
@RequiredArgsConstructor
public class MineController {

    private final MineService mineService;

    /** 四宫格：待支付 / 待使用 / 已预约 / 待评价 */
    @GetMapping("/summary")
    public Result<MineSummaryVO> summary() {
        return Result.ok(mineService.summary(UserContext.getUserId()));
    }
}
