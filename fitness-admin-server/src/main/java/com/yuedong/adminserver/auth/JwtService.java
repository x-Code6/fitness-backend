package com.yuedong.adminserver.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 管理端 JWT 服务：subject=adminId，自定义 claim "role" 携带角色。
 *
 * <p>与用户端 JwtUtil 分开实现：管理端 token 需要携带角色做 RBAC，
 * 且两套身份独立签发/校验，避免"用户 token 冒充管理员"的交叉风险。</p>
 */
public class JwtService {

    @Getter
    private final SecretKey key;

    private final long expireMinutes;

    public JwtService(String secret, long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    /** 签发管理端 token：subject=adminId，claim role */
    public String generate(Long adminId, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(adminId))
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMinutes * 60 * 1000))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析 token。
     *
     * @return claims（含 adminId 与 role）
     * @throws io.jsonwebtoken.JwtException 签名不合法或已过期
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
