package com.yuedong.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器：BCrypt（自带随机盐 + 可调慢速因子，专为密码设计）。
 *
 * <p>只引 spring-security-crypto 工具包即可获得该类，无需引入整个 Spring Security。</p>
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // strength=10：2^10 轮加盐散列，默认值；单次约几十毫秒，可防暴力枚举
        return new BCryptPasswordEncoder();
    }
}
