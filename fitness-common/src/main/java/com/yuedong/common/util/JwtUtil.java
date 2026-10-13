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
 *   <li>payload 只放 userId（subject）与 jti，不放昵称/角色等敏感或可变信息——
 *       JWT 只是签名不是加密，任何人都能解码看到内容；</li>
 *   <li>过期时间以分钟配置，到期后 parse 会抛 ExpiredJwtException，由调用方转成 401。</li>
 * </ul>
 *
 * <p>★v2 新增 jti 的意义：JWT 一旦签发，在过期前<b>服务端无法作废</b>——"禁用用户""踢下线"
 * "改密后强制重新登录"都做不到。解法不是放弃 JWT，而是给它配一张 Redis 令牌白名单：</p>
 * <ol>
 *   <li>登录时生成 jti（UUID）→ 写进 token，同时 {@code SET login:token:{jti} = userId}，
 *       TTL 与 token 有效期一致，并把 jti 记进 {@code login:user:{userId}} 这个 Set；</li>
 *   <li>网关验签通过后，再查一次 Redis：这个 jti 还在白名单里吗？不在就 401；</li>
 *   <li>要吊销某人时，按 {@code login:user:{userId}} 取出他所有 jti 删掉，立即全网失效。</li>
 * </ol>
 *
 * <p>这样既保留了"下游服务不用查库就能知道是谁"的好处，又拿回了可撤销能力。
 * 代价是网关每请求多一次 Redis O(1) 查询，以及 <b>Redis 成为登录链路的依赖</b>——
 * Redis 挂掉时是放行还是全部拒绝，必须显式决策（见 gateway 的 {@code auth.token-check-enabled}）。</p>
 */
public class JwtUtil {

    /** 签名密钥（长度必须 ≥ 32 字符，HS256 要求） */
    private final SecretKey key;

    /** token 有效时长（分钟），配置项按分钟写、对外换算成秒 */
    private final long expireMinutes;

    public JwtUtil(String secret, long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = expireMinutes;
    }

    /** 取 HS256 签名密钥对象（由 secret 字节派生） */
    private SecretKey key() {
        return this.key;
    }

    /** token 有效期（秒）。签发方拿它去设 Redis TTL，保证"白名单活得和 token 一样久" */
    public long expireSeconds() {
        return expireMinutes * 60;
    }

    /**
     * 为指定用户签发 token。
     *
     * @param userId 用户 id，写入 JWT 的 subject 字段
     * @param jti    令牌唯一标识（一般用 UUID），写入 JWT 的 jti 字段，同时是 Redis 白名单的 key
     * @return 签名后的 JWT 字符串
     */
    public String generate(Long userId, String jti) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireSeconds() * 1000))
                .signWith(key())
                .compact();
    }

    /**
     * 校验签名并解析出 userId 与 jti。
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return 解析结果
     * @throws io.jsonwebtoken.JwtException 签名不合法、已过期或缺 jti 时抛出，由调用方统一处理为 401
     */
    public JwtPayload parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // 旧版本签发的 token 里没有 jti，解析出来是 null。这里直接判定为无效而不是放行，
        // 因为"放行无 jti 的 token"等于给白名单机制开了一个永久的后门
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            throw new io.jsonwebtoken.JwtException("token 缺少 jti，视为无效令牌");
        }
        return new JwtPayload(Long.valueOf(claims.getSubject()), jti);
    }
}
