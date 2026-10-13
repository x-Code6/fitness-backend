package com.yuedong.community.mq;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.yuedong.common.constant.MqConstants;
import org.springframework.amqp.core.TopicExchange;

/**
 * RabbitMQ 拓扑（社区服务，生产者侧）：声明通知事件交换机。
 */
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange notifyExchange() {
        return new TopicExchange(MqConstants.NOTIFY_EXCHANGE, true, false);
    }
}
