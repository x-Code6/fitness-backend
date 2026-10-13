package com.yuedong.course.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置（单机模式）。
 *
 * <p>Redisson 提供比 RedisTemplate 更丰富的能力：分布式锁（RLock，
 * 可重入 + 看门狗自动续期）、限流器（RRateLimiter）等。
 * 本服务用它做课程详情缓存重建的互斥锁。</p>
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(@Value("${spring.data.redis.host}") String host,
                                         @Value("${spring.data.redis.port}") int port) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10);
        return Redisson.create(config);
    }
}
