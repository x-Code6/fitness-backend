package com.yuedong.adminserver.config;

import com.yuedong.adminserver.interceptor.AdminAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理端 Web 配置：只保留认证拦截器。
 *
 * <p>★图片上传与 {@code /uploads/**} 静态映射已迁到独立文件服务 fitness-file：
 * 本服务不再持有上传目录，也不再参与图片读写，所以这里不再注册 ResourceHandler，
 * 也不再有 file.upload-root 字段（磁盘根目录只允许有一个所有者）。</p>
 *
 * <p>★拦截器不再排除 {@code /uploads/**}：网关已把该前缀路由到 fitness-file，
 * 本服务根本收不到这类请求；留着一条永远匹配不上的排除规则只会让后来人误以为
 * 这里还在提供图片。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/**")
                // 验证码与登录是"还没有 token"的两个入口，必须放行；其余 /admin/** 全部验签
                .excludePathPatterns("/admin/auth/captcha", "/admin/auth/login");
    }
}
