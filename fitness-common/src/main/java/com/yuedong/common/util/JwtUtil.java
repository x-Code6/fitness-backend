package com.yuedong.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具（HS256 对称签名）。
 *
 * <p>设计说明：</p>
 * <ul>
 *   <li>非 Spring Bean：由各服务（user 签发、gateway 校验）用自己的配置构造实例，
 *       两边必须使用相同的 secret，token 才能验签通过；</li>
 *   <li>payload 只放 userId（主体 subject），不放敏感信息——JWT 只是签名不是加密，
 *       任何人都能解码看到内容；</li>
 *   <li>过期时间以分钟配置，到期后 parse 会抛 ExpiredJwtException，由调用方转成 401。</li>
 * </ul>
 */
public class JwtUtil {

    /** 签名密钥（长度必须 ≥ 32 字符，HS256 要求） */
    private final SecretKey key;

    /** token 有效时长（分钟） */
    private final long expireMinutes;

    public JwtUtil(String secret, long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    /** 取 HS256 签名密钥对象（由 secret 字节派生） */
    private SecretKey key() {
        return this.key;
    }

    /**
     * 为指定用户签发 token。
     *
     * @param userId 用户 id（写入 JWT 的 subject 字段）
     * @return 签名后的 JWT 字符串
     */
    public String generate(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMinutes * 60 * 1000))
                .signWith(key())
                .compact();
    }

    /**
     * 校验并解析 token，取出用户 id。
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return 用户 id
     * @throws io.jsonwebtoken.JwtException 签名不合法或已过期时抛出，由调用方统一处理为 401
     */
    public Long parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.valueOf(claims.getSubject());
    }
}
