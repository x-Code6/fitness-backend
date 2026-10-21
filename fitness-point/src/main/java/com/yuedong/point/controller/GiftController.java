package com.yuedong.point.controller;

import com.yuedong.common.idempotent.Idempotent;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.point.model.vo.GiftVO;
import com.yuedong.point.service.GiftService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分商城接口（需登录）。
 */
@RestController
@RequestMapping("/point")
@RequiredArgsConstructor
public class GiftController {

    private final GiftService giftService;

    /** 上架商品列表 */
    @GetMapping("/gifts")
    public Result<List<GiftVO>> gifts() {
        return Result.ok(giftService.list());
    }

    /**
     * 积分兑换（CAS 扣积分 + 扣库存，同事务）。
     *
     * @param requestId 客户端生成的本次请求号。带上它以后，网络重试/用户重复点击
     *                  都会命中同一个积分流水 {@code bizId}，不会扣两次分。
     *                  不传也能用，但只能靠 3 秒防重窗口挡住手抖。
     */
    @Idempotent(prefix = "point:exchange", expireSecond = 3)
    @PostMapping("/exchange")
    public Result<Void> exchange(@RequestParam Long giftId,
                                 @RequestParam(required = false) String requestId) {
        giftService.exchange(UserContext.getUserId(), giftId, requestId);
        return Result.ok();
    }
}
