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
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * RabbitMQ 拓扑（订单服务）。
 *
 * <p>三组资源：</p>
 * <ol>
 *   <li>延迟取消：ex.order.delay（x-delayed-message 插件交换机）→ q.order.timeout，
 *       消息头 x-delay=15 分钟，用于"下单未支付超时自动取消"；</li>
 *   <li>死信兜底：消费 nack(requeue=false) 后转入 q.order.timeout.dlq，看得见也能人工重投；</li>
 *   <li>publisher confirm：消息到达交换机后回调，投递失败打错误日志（可扩展重发）。</li>
 * </ol>
 *
 * <p>★<b>这里不再有"秒杀建单"那组队列</b>：秒杀改成"提交时同步落单、异步只做收尾"之后，
 * 建单这件事的真相就在 t_booking 里，不需要一条消息去驱动它被创建。
 * 留着两套入口会让"一人一单"出现两份实现——而这两份实现一旦分叉，
 * 症状是"同一个人抢出两张单"，属于最难回滚的一类线上事故。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Configuration
public class RabbitConfig {

    // ---------- 站内通知事件（user 服务消费后生成站内消息） ----------

    @Bean
    public TopicExchange notifyExchange() {
        return new TopicExchange(MqConstants.NOTIFY_EXCHANGE, true, false);
    }

    // ---------- 死信交换机与队列 ----------
    @Bean
    public DirectExchange orderDlx() {
        return new DirectExchange(MqConstants.ORDER_DLX, true, false);
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
     *
     * <p><b>为什么用 RabbitTemplateCustomizer，而不是自己声明一个 RabbitTemplate bean。</b>
     * 原先的写法是 {@code @Bean RabbitTemplate confirmCallback(RabbitTemplate rabbitTemplate)}
     * ——往方法参数里塞一个 RabbitTemplate、设置完回调再把它原样 return 出去，看着像"装饰一下"，
     * 实际等于<b>由我们自己定义了一个 RabbitTemplate bean</b>。两个后果连在一起：
     * <ol>
     *   <li>Boot 自动配置的那个 RabbitTemplate 上标了 {@code @ConditionalOnMissingBean}，
     *       一见我们已经有同类型的 bean 就退出，于是容器里"唯一的 RabbitTemplate"变成了这个方法本身；</li>
     *   <li>它的入参又要注入 RabbitTemplate，注入的只能是它自己 →
     *       任何用到 RabbitTemplate 的 bean 都会被拖进环，启动即失败，
     *       报的就是 {@code bookingController -> bookingServiceImpl -> confirmCallback} 这条循环链。</li>
     * </ol>
     * 现在的写法不新增 RabbitTemplate，只交出一个"装回调"的动作：Boot 在<b>创建</b>
     * 自动配置那个 RabbitTemplate 时会把所有 RabbitTemplateCustomizer 依次 apply 上去
     * （见 {@code RabbitAutoConfiguration.RabbitTemplateConfiguration#rabbitTemplate}），
     * 依赖图里没有回头边，publisher-confirm-type 这类 yml 配置也照常生效。</p>
     */
    @Bean
    public RabbitTemplateCustomizer confirmCallbackCustomizer() {
        return rabbitTemplate -> rabbitTemplate.setConfirmCallback(
                (CorrelationData correlationData, boolean ack, String cause) -> {
                    if (!ack) {
                        log.error("MQ 消息未到达交换机: cause={}, correlation={}", cause, correlationData);
                    }
                });
    }
}
