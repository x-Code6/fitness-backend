package com.yuedong.course.controller;

import com.yuedong.common.result.Result;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.course.service.SeckillActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 秒杀活动接口（列表免登录；下单在 order 服务的 /seckill/book）。
 */
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillActivityService seckillActivityService;

    /**
     * 进行中的秒杀课程列表（含秒杀价/实时库存/倒计时截止时间戳）。
     */
    @GetMapping("/list")
    public Result<List<CourseVO>> list() {
        return Result.ok(seckillActivityService.listActive());
    }
}
