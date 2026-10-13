package com.yuedong.point.mq;

import com.yuedong.common.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑（point 服务，消费方）。
 *
 * <p>声明课程事件交换机、本服务的事件队列（带死信配置）与绑定；
 * 与 course 服务生产者侧的交换机声明幂等，先启动谁都没问题。</p>
 */
@Configuration
public class RabbitConfig {


    /** 事件队列：消费 nack 后转入死信，人工排查/定时补偿 */
    @Bean
    public Queue courseFinishedQueue() {
        return QueueBuilder.durable(MqConstants.POINT_COURSE_FINISHED_QUEUE)
                .deadLetterExchange(MqConstants.POINT_DLX)
                .deadLetterRoutingKey(MqConstants.POINT_DLQ_KEY)
                .build();
    }

    @Bean
    public DirectExchange courseEventExchange() {
        return new DirectExchange(MqConstants.COURSE_EVENT_EXCHANGE, true, false);
    }

    @Bean
    public Binding courseFinishedBinding() {
        return BindingBuilder.bind(courseFinishedQueue()).to(courseEventExchange())
                .with(MqConstants.COURSE_FINISHED_KEY);
    }

    @Bean
    public DirectExchange pointDlx() {
        return new DirectExchange(MqConstants.POINT_DLX, true, false);
    }

    @Bean
    public Queue courseFinishedDlq() {
        return new Queue(MqConstants.POINT_COURSE_FINISHED_DLQ, true);
    }

    @Bean
    public Binding courseFinishedDlqBinding() {
        return BindingBuilder.bind(courseFinishedDlq()).to(pointDlx()).with(MqConstants.POINT_DLQ_KEY);
    }

    // ---------- 站内通知事件交换机（生产者：point/order/community；消费者：user 服务） ----------
    @Bean
    public org.springframework.amqp.core.TopicExchange notifyExchange() {
        return new org.springframework.amqp.core.TopicExchange(MqConstants.NOTIFY_EXCHANGE, true, false);
    }
}
