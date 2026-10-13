package com.yuedong.course.api.client;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CouponUsableDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * 优惠券契约（课程服务承载优惠券域；订单服务下单用券时 Feign 调用）。
 */
@FeignClient(name = "fitness-course")
public interface CouponClient {

    /** 校验持券可用性（amount = 订单原价） */
    @GetMapping("/inner/coupon/info")
    Result<CouponUsableDTO> info(@RequestParam("recordId") Long recordId,
                                 @RequestParam("userId") Long userId,
                                 @RequestParam("amount") BigDecimal amount);

    /** 核销用券（UNUSED → USED，绑定订单号） */
    @PostMapping("/inner/coupon/use")
    Result<Boolean> use(@RequestParam("recordId") Long recordId,
                        @RequestParam("userId") Long userId,
                        @RequestParam("orderNo") String orderNo);

    /** 退券（订单取消/超时回退为未使用） */
    @PostMapping("/inner/coupon/refund")
    Result<Boolean> refund(@RequestParam("recordId") Long recordId);
}
