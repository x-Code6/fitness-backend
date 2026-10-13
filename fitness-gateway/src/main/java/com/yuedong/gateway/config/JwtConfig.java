package com.yuedong.gateway.config;

import com.yuedong.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关的 JWT 配置。
 *
 * <p>secret 必须与 user-service 完全一致（签发与验签同钥），
 * 生产环境应放入 Nacos 配置中心或环境变量，不要写死在代码里。</p>
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil(@Value("${jwt.secret}") String secret,
                           @Value("${jwt.expire-minutes}") long expireMinutes) {
        return new JwtUtil(secret, expireMinutes);
    }
}
