package com.yuedong.order.service;

import com.yuedong.order.model.vo.SeckillBookVO;
import com.yuedong.order.model.vo.SeckillResultVO;

/**
 * 秒杀业务接口（异步排队 + 轮询结果）。
 */
public interface SeckillService {

    /**
     * 秒杀下单：Lua 原子校验（库存 + 一人一单）→ 发 MQ → 返回排队号。
     * 真正的订单创建由消费者异步完成。
     */
    SeckillBookVO book(Long userId, Long courseId);

    /**
     * 轮询排队结果。
     *
     * @return QUEUEING / SUCCESS(带订单号) / FAIL(带原因)
     */
    SeckillResultVO result(String seqId);

    /**
     * 写入排队结果（消费者建单成功/失败后调用，前端轮询即可感知）。
     *
     * @param status  SUCCESS / FAIL
     * @param orderNo 成功时的订单号
     * @param reason  失败原因
     */
    void writeResult(String seqId, String status, String orderNo, String reason);
}
