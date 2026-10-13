package com.yuedong.order.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单超时取消消息（延迟 15 分钟后由 q.order.timeout 消费）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutMessage {

    /** 订单号 */
    private String orderNo;

    /** 用户 id（释放名额/日志用） */
    private Long userId;
}
