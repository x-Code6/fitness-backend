package com.yuedong.user.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import com.yuedong.common.constant.MqConstants;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑（user 服务，通知消费方）。
 *
 * <p>ex.notify.exchange（topic）以 notify.# 绑定本服务队列：
 * 任何业务域按 notify.<domain> 发通知事件都会被接收。</p>
 */
@Configuration
public class RabbitConfig {

    public static final String USER_NOTIFY_QUEUE = "q.user.notify";

    @Bean
    public TopicExchange notifyExchange() {
        return new TopicExchange(MqConstants.NOTIFY_EXCHANGE, true, false);
    }

    @Bean
    public Queue userNotifyQueue() {
        return QueueBuilder.durable(USER_NOTIFY_QUEUE).build();
    }

    @Bean
    public Binding userNotifyBinding() {
        return BindingBuilder.bind(userNotifyQueue()).to(notifyExchange()).with("notify.#");
    }
}
