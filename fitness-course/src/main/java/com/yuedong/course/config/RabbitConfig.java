package com.yuedong.course.config;

import com.yuedong.common.constant.MqConstants;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑（生产者侧）。
 *
 * <p>本服务只声明自己要往里发消息的交换机；
 * 队列和绑定关系由消费方（point 服务）声明——两边声明幂等，谁先启动都行。
 * 拓扑名称统一引用 MqConstants，保证生产/消费两侧字符串一致。</p>
 */
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange courseEventExchange() {
        return new TopicExchange(MqConstants.COURSE_EVENT_EXCHANGE, true, false);
    }
}
