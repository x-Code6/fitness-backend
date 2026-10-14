package com.yuedong.adminserver.config;

import com.yuedong.adminserver.interceptor.AdminAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 管理端 Web 配置：① 认证拦截器（排除验证码/登录/静态图片）；
 * ② 把上传目录挂成 HTTP 静态资源（D19）。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    /** 上传根目录，与 FileStorageService 用同一个配置项（两处必须同源，否则"写进去读不出来"） */
    @Value("${file.upload-root:./uploads}")
    private String uploadRoot;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/**")
                // ★/uploads/** 必须排除：<img src="..."> 不会带 Authorization 头，
                //   不排除的话管理端所有缩略图都是 401 的破图。
                //   这里放行的只是"读一张已存在的图片"，不含任何写能力
                .excludePathPatterns("/admin/auth/captcha", "/admin/auth/login", "/uploads/**");
    }

    /**
     * 静态映射：HTTP {@code /uploads/**} → 磁盘 {@code {file.upload-root}/**}。
     *
     * <p>★用 Spring 的资源处理器而不是自己写一个返回 {@code byte[]} 的下载接口：
     * 资源处理器自带 Range / Last-Modified / Content-Type 推断，
     * 而自己拼字节数组时最容易犯的错是把磁盘绝对路径回显出去、
     * 或者忘了归一化让 {@code ../../} 读到别处的文件。</p>
     *
     * <p>{@code addResourceLocations} 给的是<b>目录</b> URI（必须以 / 结尾），
     * Spring 只会在这棵子树里找文件。</p>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadRoot).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
