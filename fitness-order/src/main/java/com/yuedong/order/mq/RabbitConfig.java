package com.yuedong.order.mq;

import com.yuedong.common.constant.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * RabbitMQ 拓扑（订单服务）。
 *
 * <p>四组资源：</p>
 * <ol>
 *   <li>秒杀建单：ex.order.seckill → q.order.seckill.create（消费端建订单，削峰）；</li>
 *   <li>延迟取消：ex.order.delay（x-delayed-message 插件交换机）→ q.order.timeout，
 *       消息头 x-delay=15 分钟，用于"下单未支付超时自动取消"；</li>
 *   <li>死信兜底：建单队列消费抛意外异常时 nack 进 q.order.seckill.dlq，人工/定时补偿；</li>
 *   <li>publisher confirm：消息到达交换机后回调，投递失败打错误日志（可扩展重发）。</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
@Configuration
public class RabbitConfig {

    // ---------- 站内通知事件（user 服务消费后生成站内消息） ----------

    @Bean
    public org.springframework.amqp.core.TopicExchange notifyExchange() {
        return new org.springframework.amqp.core.TopicExchange(MqConstants.NOTIFY_EXCHANGE, true, false);
    }

    // ---------- 秒杀建单 ----------

    // ---------- 死信 ----------

    // ---------- 延迟取消 ----------

    // ---------- 秒杀建单队列（带死信配置：消费 nack 后转入死信） ----------
    @Bean
    public Queue seckillCreateQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_SECKILL_CREATE_QUEUE)
                .deadLetterExchange(MqConstants.ORDER_DLX)
                .deadLetterRoutingKey(MqConstants.ORDER_DLQ_KEY)
                .build();
    }

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(MqConstants.ORDER_SECKILL_EXCHANGE, true, false);
    }

    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillCreateQueue()).to(seckillExchange())
                .with(MqConstants.ORDER_SECKILL_CREATE_KEY);
    }

    // ---------- 死信交换机与队列 ----------
    @Bean
    public DirectExchange orderDlx() {
        return new DirectExchange(MqConstants.ORDER_DLX, true, false);
    }

    @Bean
    public Queue seckillDlq() {
        return new Queue(MqConstants.ORDER_SECKILL_DLQ, true);
    }

    @Bean
    public Binding seckillDlqBinding() {
        return BindingBuilder.bind(seckillDlq()).to(orderDlx()).with("order.dlq");
    }

    // ---------- 延迟交换机（需要 broker 安装 rabbitmq_delayed_message_exchange 插件） ----------
    @Bean
    public CustomExchange orderDelayExchange() {
        return new CustomExchange(MqConstants.ORDER_DELAY_EXCHANGE, "x-delayed-message",
                true, false, Map.of("x-delayed-type", "direct"));
    }

    @Bean
    public Queue orderTimeoutQueue() {
        // ★P0-10：这条队列以前没有死信配置。消费失败时 basicNack(requeue=false)
        //   在没有 DLX 的队列上等于"消息被静默丢弃"——订单永远没人取消，
        //   而日志里只有一行 error，看起来像"已经处理过了"。
        //   有 DLX 之后它落在 q.order.timeout.dlq 里，看得见、也能人工重投。
        // ⚠️ 队列参数<b>不能对已存在的队列改</b>：RabbitMQ 会以 PRECONDITION_FAILED 拒绝声明。
        //   本机/测试环境要么先删掉这个队列，要么换个队列名；这也是为什么这类改动要跟部署一起做一次
        return QueueBuilder.durable(MqConstants.ORDER_TIMEOUT_QUEUE)
                .deadLetterExchange(MqConstants.ORDER_DLX)
                .deadLetterRoutingKey(MqConstants.ORDER_TIMEOUT_DLQ_KEY)
                .build();
    }

    /** 延迟取消队列的死信队列（P0-10） */
    @Bean
    public Queue orderTimeoutDlq() {
        return new Queue(MqConstants.ORDER_TIMEOUT_DLQ, true);
    }

    @Bean
    public Binding orderTimeoutDlqBinding() {
        return BindingBuilder.bind(orderTimeoutDlq()).to(orderDlx())
                .with(MqConstants.ORDER_TIMEOUT_DLQ_KEY);
    }

    @Bean
    public Binding orderTimeoutBinding() {
        // CustomExchange 的绑定需要以 noargs() 收尾，返回 Binding
        return BindingBuilder.bind(orderTimeoutQueue()).to(orderDelayExchange())
                .with(MqConstants.ORDER_TIMEOUT_KEY).noargs();
    }

    /**
     * 生产者确认回调：消息是否成功到达交换机（ack=false 时打错误日志，可扩展重发/落库补偿）。
     */
    @Configuration
    static class ConfirmCallbackConfig {
        @Bean
        public RabbitTemplate confirmCallback(RabbitTemplate rabbitTemplate) {
            rabbitTemplate.setConfirmCallback((CorrelationData cd, boolean ack, String cause) -> {
                if (!ack) {
                    log.error("MQ 消息未到达交换机: cause={}, correlation={}", cause, cd);
                }
            });
            return rabbitTemplate;
        }
    }
}
