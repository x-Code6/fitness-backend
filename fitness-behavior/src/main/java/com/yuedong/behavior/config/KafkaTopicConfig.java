package com.yuedong.behavior.config;

import com.yuedong.common.constant.MqConstants;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka topic 显式声明（修 P1-9 第 3 条）。
 *
 * <p>★<b>以前全项目没有任何地方建这个 topic</b>，靠 broker 的
 * {@code auto.create.topics.enable=true} 在第一次生产/消费时自动建。
 * 自动建用的是 broker 默认配置，而默认<b>分区数是 1</b> ——
 * 于是代码注释与设计文档里的"3 分区、同设备事件有序"在真实环境里只落在 1 个分区上：
 * 顺序仍然对，但吞吐是单点，而且这个偏差<b>永远不报错</b>，只会一直存在。</p>
 *
 * <p>声明成 Bean 之后，自动配置出来的 {@code KafkaAdmin} 会在启动时建这个 topic
 * （已存在则不动）。⚠️ 它<b>不会修改已存在 topic 的分区数</b>：
 * 测试环境里如果它已经被自动建成 1 分区，要么删掉 topic 重来，
 * 要么手动 {@code --partitions 3} 扩容（Kafka 只能加分区不能减）。
 * 分区数一变，"按 deviceId 哈希到固定分区"这条保序前提要重新核对一次。</p>
 */
@Configuration
public class KafkaTopicConfig {

    /**
     * 埋点事件 topic。
     *
     * @param partitions 3 个分区（与文档口径一致）
     * @param replicas   ★1。本机与测试 VM 都只有一个 broker，写 3 会直接建不出来
     */
    @Bean
    public NewTopic behaviorTopic() {
        return new NewTopic(MqConstants.BEHAVIOR_TOPIC, 3, (short) 1);
    }
}
