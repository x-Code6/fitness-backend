package com.yuedong.common.util;

import lombok.Getter;

/**
 * JWT 解析结果（只装本项目真正需要的两个字段）。
 *
 * <p>为什么不直接返回 {@code Claims}：那会把 jjwt 的类型泄漏给所有调用方，
 * 换库就要改一片代码；收在这里只暴露 userId 与 jti，调用方拿到的是纯 Java 类型。</p>
 *
 * <p>为什么一次解析返回两个值而不是提供 {@code parseUserId()} + {@code parseJti()} 两个方法：
 * 后者会把同一个 token 验签两次。HS256 验签不贵，但网关是每个请求都走的热点，
 * 少一次没必要的计算比少写两行代码重要。</p>
 */
@Getter
public class JwtPayload {

    /** 用户（或管理员）id，来自 JWT 的 subject */
    private final Long id;

    /**
     * 令牌唯一标识，来自 JWT 的 jti（JWT ID）claim。
     *
     * <p>它是"这一次登录"的身份证：同一账号登录三次会有三个不同的 jti，
     * 于是可以只吊销其中某一个（踢下线），也可以吊销全部（禁用账号）。</p>
     */
    private final String jti;

    public JwtPayload(Long id, String jti) {
        this.id = id;
        this.jti = jti;
    }
}
