package com.yuedong.adminserver.config;

import com.yuedong.adminserver.auth.JwtService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 管理端安全组件：独立 JWT（含 role 声明）+ BCrypt 密码编码器。
 */
@Configuration
public class AdminSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 管理端专用 JwtUtil。subject=adminId；
     * role 放在自定义 claim，由 AdminJwtHelper 解析（见下）。
     * 生产环境 secret 应放配置中心。
     */
    @Bean
    public JwtService jwtService(@Value("${admin-jwt.secret}") String secret,
                                 @Value("${admin-jwt.expire-minutes}") long expireMinutes) {
        return new JwtService(secret, expireMinutes);
    }
}
