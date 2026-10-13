package com.yuedong.user.config;

import com.yuedong.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 工具 Bean：secret 与网关保持一致（user 签发 / gateway 验签）。
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil(@Value("${jwt.secret}") String secret,
                           @Value("${jwt.expire-minutes}") long expireMinutes) {
        return new JwtUtil(secret, expireMinutes);
    }
}
