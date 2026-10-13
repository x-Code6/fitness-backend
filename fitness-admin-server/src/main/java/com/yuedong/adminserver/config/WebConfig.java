package com.yuedong.adminserver.config;

import com.yuedong.adminserver.interceptor.AdminAuthInterceptor;
import com.yuedong.adminserver.auth.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理端 Web 配置：注册认证拦截器（排除验证码/登录接口）。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/admin/auth/captcha", "/admin/auth/login");
    }
}
