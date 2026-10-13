package com.yuedong.common.constant;

/**
 * HTTP 头/鉴权相关常量（网关、common 拦截器、Feign 透传三方共享）。
 */
public final class HttpConstants {

    private HttpConstants() {
    }

    /**
     * 网关校验 JWT 后写入的用户 id 透传头。
     * 下游服务（UserContextInterceptor）读它填充 UserContext；
     * 网关会先 remove 再 add，防止外部伪造。
     */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 认证头 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** Bearer token 前缀 */
    public static final String BEARER_PREFIX = "Bearer ";
}
