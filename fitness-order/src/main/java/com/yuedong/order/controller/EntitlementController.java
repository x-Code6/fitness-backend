package com.yuedong.order.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.vo.EntitlementVO;
import com.yuedong.order.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 我的凭证接口（需登录）。
 *
 * <p>网关路由：{@code /api/entitlement/**} → 本服务 {@code /entitlement/**}。
 * ★路径放在 order 域而不是 course 域：凭证是"付了钱之后"的东西，
 * 数据在 {@code yd_order.t_entitlement}，接口位置由<b>数据归属</b>决定
 * ——和 {@code /reservation/slots} 放 order 是同一个理由。</p>
 *
 * <p>这里只有读接口，没有写接口：出票由支付链路触发，扣次由预约链路触发，
 * 过期由定时任务触发。<b>凭证状态没有"用户自己能改"的入口</b>，
 * 这正是它不该被前端直接操作的原因。</p>
 */
@RestController
@RequestMapping("/entitlement")
@RequiredArgsConstructor
public class EntitlementController {

    private final EntitlementService entitlementService;

    /**
     * 我的凭证列表。
     *
     * @param courseId   只看这门课（进入课程页时查"我已购"），不传看全部
     * @param usableOnly true 时只返回当前可用的（预约页的下拉数据），默认返回全部含历史
     */
    @GetMapping("/mine")
    public Result<List<EntitlementVO>> mine(@RequestParam(required = false) Long courseId,
                                            @RequestParam(required = false) Boolean usableOnly) {
        return Result.ok(entitlementService.listMine(UserContext.getUserId(), courseId, usableOnly));
    }
}
