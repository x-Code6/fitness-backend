package com.yuedong.point.controller;

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

    /** 积分兑换（CAS 扣积分 + 扣库存，同事务） */
    @PostMapping("/exchange")
    public Result<Void> exchange(@RequestParam Long giftId) {
        giftService.exchange(UserContext.getUserId(), giftId);
        return Result.ok();
    }
}
