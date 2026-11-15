package com.yuedong.behavior.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费失败处理：有限重试 + 死信。
 *
 * <p>★为什么必须有一个明确的 error handler：监听器现在把异常往外抛，
 * 如果没人接住，Spring Kafka 的默认行为在不同版本/配置下不一样——可能无限重试，
 * 也可能重试几次后直接提交 offset，消息就静默丢了。这里把规则写死：</p>
 * <ul>
 *   <li>失败后重试，间隔 2 秒，共 3 次尝试（首次 + 2 次重试）；</li>
 *   <li>仍失败则投递到 {@code yd-behavior-event.DLT}，保留原始消息和异常头；</li>
 *   <li>死信投递完成后由容器按手动 ack 规则提交原消息的 offset，不再阻塞分区。</li>
 * </ul>
 *
 * <p>★死信不是"处理完了"，而是"不再自动重试"。运营/开发需要监控 DLT 并重放。
 * 对埋点来说，这比无限重试把分区卡死、或静默丢消息都更可控。</p>
 */
@Configuration
public class KafkaConsumerConfig {

    /**
     * @param kafkaTemplate 复用项目里已配置的 String 序列化生产者
     */
    @Bean
    public DefaultErrorHandler behaviorErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 默认投递到 <原topic>.DLT，且分区号与原记录一致（DLT topic 必须同分区数）
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3));
        // 死信投递完成后提交原 offset，避免同一条坏消息在分区里反复回放
        handler.setAckAfterHandle(true);
        // 格式/字段错误重试也不会好，直接进 DLT；只让 DB/网络这类瞬时故障走重试
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
