package com.yuedong.order.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.dto.SeckillBookDTO;
import com.yuedong.order.model.vo.SeckillBookVO;
import com.yuedong.order.model.vo.SeckillResultVO;
import com.yuedong.order.service.SeckillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀下单接口（需登录；列表在课程服务 /seckill/list）。
 */
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    /**
     * 抢课：Lua 原子校验后立即返回排队号；订单由消费者异步创建。
     *
     * <p>前端拿到 seqId 后每 500ms 轮询 /seckill/result。</p>
     */
    @PostMapping("/book")
    public Result<SeckillBookVO> book(@Valid @RequestBody SeckillBookDTO dto) {
        return Result.ok(seckillService.book(UserContext.getUserId(), dto.getCourseId()));
    }

    /**
     * 轮询排队结果。
     *
     * @param seqId 抢课时返回的排队号
     */
    @GetMapping("/result")
    public Result<SeckillResultVO> result(@RequestParam String seqId) {
        return Result.ok(seckillService.result(seqId));
    }
}
