package com.yuedong.order.controller.inner;

import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.RatingStatsDTO;
import com.yuedong.order.service.RatingService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评价统计内部接口（course 服务取门店/课程分数；网关硬拒外部直访 {@code /inner/**}）。
 * 签名与 {@code fitness-order-api} 的 {@code RatingClient} 一一对应。
 */
@RestController
@RequestMapping("/inner/rating")
@RequiredArgsConstructor
@Validated
public class InnerRatingController {

    private final RatingService ratingService;

    /**
     * 批量取评分统计。
     *
     * @param type STORE / COURSE / COACH
     * @param ids  目标 id（逗号分隔，Feign 会把 List 展开成重复参数）
     */
    @GetMapping("/stats")
    public Result<List<RatingStatsDTO>> stats(@RequestParam String type,
                                              @RequestParam List<Long> ids) {
        return Result.ok(ratingService.stats(type, ids));
    }
}
