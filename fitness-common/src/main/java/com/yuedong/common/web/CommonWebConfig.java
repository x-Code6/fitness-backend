package com.yuedong.common.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 公共 Web 配置：把用户上下文拦截器注册到所有 Servlet 业务服务。
 *
 * <p>业务服务的 @SpringBootApplication 都配置了 scanBasePackages = "com.yuedong"，
 * 会自动扫描到本配置类，各服务无需再写任何代码。
 * 网关是 WebFlux 应用且只扫描自己的包，不受影响。</p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 全路径注册：拦截器内部对"没有 X-User-Id 头的匿名请求"不做任何处理，
        // 因此不需要按服务配置白名单
        registry.addInterceptor(new UserContextInterceptor());
    }
}
