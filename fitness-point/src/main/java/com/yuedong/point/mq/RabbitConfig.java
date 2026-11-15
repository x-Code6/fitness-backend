package com.yuedong.point.mq;

import com.yuedong.common.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑（point 服务，消费方）。
 *
 * <p>声明课程事件交换机、本服务的事件队列（带死信配置）与绑定。</p>
 *
 * <p>★<b>交换机类型必须与 course 生产者侧声明的一字不差</b>：RabbitMQ 的交换机是
 * 全局资源，同名交换机第二次声明时如果 type、durable、autoDelete 任何一个参数对不上，
 * broker 直接回 {@code PRECONDITION_FAILED - inequivalent arg 'type'}，
 * 而且这条错误是从"启动时声明拓扑"那个后台线程抛的——服务能起来，但队列永远绑不上，
 * 表现就是"完课了积分却没加"，很难往 MQ 声明这块想。
 * course 用的是 TopicExchange（见 {@code com.yuedong.course.mq.RabbitConfig}），
 * 这里以前写的是 DirectExchange，两边谁先启动都会撞车，现在统一成 Topic。
 * 路由键 {@code course.finished} 在 Topic 下是精确匹配，行为不变。</p>
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

    /** 课程事件交换机——类型必须和 course 服务声明的一致（Topic），见类注释 */
    @Bean
    public TopicExchange courseEventExchange() {
        return new TopicExchange(MqConstants.COURSE_EVENT_EXCHANGE, true, false);
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
    public TopicExchange notifyExchange() {
        return new TopicExchange(MqConstants.NOTIFY_EXCHANGE, true, false);
    }
}
