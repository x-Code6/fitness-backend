package com.yuedong.common.idempotent;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 幂等防重切面的装配入口。
 *
 * <p>为什么单独一个配置类而不给切面标 {@code @Component}：各业务服务的启动类都是
 * {@code @SpringBootApplication(scanBasePackages = "com.yuedong")}，扫到 {@code @Component}
 * 就会无条件创建 Bean。而本切面有两个前提不成立时必须不装配：</p>
 * <ul>
 *   <li>当前是 WebFlux 应用（网关）——它没有 Servlet 请求上下文，也没有 Redis；</li>
 *   <li>类路径上没有 Redis / AspectJ——有些服务确实不需要防重切面。</li>
 * </ul>
 *
 * <p>用 {@code @ConditionalOnWebApplication(SERVLET)} + {@code @ConditionalOnClass} 双重门控，
 * 保证网关引 common 时不会被拉起一个连不上 Redis 的 Bean。</p>
 *
 * <p>注意这里<b>故意不加</b> {@code @ConditionalOnBean(StringRedisTemplate.class)}：
 * 让 {@code @Bean} 方法的入参直接依赖它。这样"某个服务想用 @Idempotent 但没配 Redis"
 * 会在启动时明确报错（NoSuchBeanDefinition），而不是注解静默失效——
 * <b>静默失效的防重比没有防重更危险</b>，因为代码看起来是加了保护的。</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({StringRedisTemplate.class, ProceedingJoinPoint.class})
public class IdempotentConfig {

    /**
     * 注册切面。切面里标了 {@code @Aspect}，Spring Boot 的 AOP 自动配置会把它织入生效，
     * 前提是类路径上有 aspectj 相关依赖（由 spring-boot-starter-aop 或 amqp starter 带入）。
     */
    @Bean
    public IdempotentAspect idempotentAspect(StringRedisTemplate stringRedisTemplate) {
        log.info("接口防重切面已启用（key 前缀 idempotent:api:，Redis 客户端 StringRedisTemplate）");
        return new IdempotentAspect(stringRedisTemplate);
    }
}
