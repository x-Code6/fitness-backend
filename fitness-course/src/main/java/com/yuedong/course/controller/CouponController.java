package com.yuedong.course.controller;

import com.yuedong.common.idempotent.Idempotent;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.course.model.vo.CouponVO;
import com.yuedong.course.model.vo.MyCouponVO;
import com.yuedong.course.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券接口（需登录；领券中心列表可匿名浏览）。
 *
 * <p>网关路由：/api/coupon/** → 本服务 /coupon/**。</p>
 */
@RestController
@RequestMapping("/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    /** 领券中心：可领的券列表（标记当前用户是否已领） */
    @GetMapping("/list")
    public Result<List<CouponVO>> list() {
        return Result.ok(couponService.listCenter(UserContext.getUserId()));
    }

    /**
     * 领券。
     *
     * <p>★加幂等（三档里的"值得防"）：双击会连发两个请求，秒杀券那条路径上
     * 第一个已经扣了 Redis 库存、第二个也过了 Lua 判断，最后写出两条 DB 记录——
     * 用户白得一张券、库存少一份。幂等窗口把第二次提交直接挡在门外，
     * 而<b>最终防线仍是 DB</b>（普通券靠 COUNT 判断、秒杀券靠 Redis + 账本）。</p>
     */
    @Idempotent(prefix = "couponReceive", expireSecond = 3)
    @PostMapping("/receive")
    public Result<Void> receive(@RequestParam Long couponId) {
        couponService.receive(UserContext.getUserId(), couponId);
        return Result.ok();
    }

    /** 我的券 */
    @GetMapping("/my")
    public Result<List<MyCouponVO>> my() {
        return Result.ok(couponService.myCoupons(UserContext.getUserId()));
    }
}
