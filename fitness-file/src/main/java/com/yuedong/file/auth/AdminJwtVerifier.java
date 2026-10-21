package com.yuedong.file.auth;

import com.yuedong.common.constant.HttpConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 管理端 JWT 校验器。
 *
 * <p>★为什么不查 admin 表：管理端 token 由 admin-server 用 {@code admin-jwt.secret} 签发，
 * 本服务用同一个密钥验签成功，就证明"它确实是我们签发的、且未过期"。
 * 文件服务不应该为了传一张图去复制一份管理员库（跨库查询、连接数、权限同步都是额外成本），
 * 也不需要在文件服务里做 RBAC——上传/查列表不区分 SUPER/OPERATOR。</p>
 *
 * <p>★与 admin-server 的 {@code AdminAuthInterceptor} 区别：那边还要做 @RequireRole 与
 * AdminContext 填充；这里只做"验签 + 取 adminId/role"，是一个纯函数式的组件。</p>
 */
@Slf4j
@Component
public class AdminJwtVerifier {

    /** 验签密钥与 admin-server 的 admin-jwt.secret 必须完全一致，否则管理端 token 全部验不过 */
    private final SecretKey key;

    public AdminJwtVerifier(@Value("${admin-jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 Authorization 头。
     *
     * @param authorization 形如 {@code Bearer xxx.yyy.zzz}
     * @return 管理员身份（adminId + role）
     * @throws BusinessException 头缺失抛 UNAUTHORIZED；签名/格式/过期不合法抛 TOKEN_INVALID
     */
    public AdminIdentity verify(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (!authorization.startsWith(HttpConstants.BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        String token = authorization.substring(HttpConstants.BEARER_PREFIX.length()).trim();
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long adminId = Long.valueOf(claims.getSubject());
            String role = claims.get("role", String.class);
            return new AdminIdentity(adminId, role == null ? "" : role);
        } catch (Exception e) {
            // ★不把具体原因（签名错/已过期/格式错）返回给前端，避免给攻击者试探信息；日志里留原因
            log.warn("管理端 token 校验失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }

    /**
     * 管理员身份的最小快照。
     *
     * <p>用 record 而不是 Map：字段固定、类型明确，调用方拿不到不存在的 key，
     * 也不用在编译期之外猜"到底是 adminId 还是 userId"。</p>
     */
    public record AdminIdentity(Long adminId, String role) {
    }
}
