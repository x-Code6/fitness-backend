package com.yuedong.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.common.constant.HttpConstants;
import com.yuedong.common.util.JwtUtil;
import com.yuedong.gateway.properties.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 全局鉴权过滤器（Ordered = 最高优先级，最先执行）。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>禁止外部访问服务间内部接口（/inner/**，只允许服务间经 Nacos 直连）；</li>
 *   <li>白名单放行（登录/注册/埋点上报等）；</li>
 *   <li>校验 JWT；</li>
 *   <li>验签通过后把 userId 写入 X-User-Id 请求头透传给下游服务——
 *       下游服务不再重复解析 JWT，只读这个头（也杜绝了外部伪造：
 *       外部无法穿透网关塞这个头，网关会先覆盖/清掉它）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final AuthProperties authProperties;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // ===== 第 1 步：内部接口一律拒绝外部访问 =====
        // 服务间调用走 Nacos 直连（不经过网关），/inner/** 出现在网关说明是外部请求
        if (path.contains("/inner/")) {
            return deny(exchange, ErrorCode.FORBIDDEN, "内部接口禁止外部访问");
        }

        // ===== 第 2 步：白名单直接放行 =====
        boolean whitelisted = authProperties.getWhitelist().stream().anyMatch(path::startsWith);
        if (whitelisted) {
            // 清掉外部可能伪造的 X-User-Id，防止冒充
            ServerHttpRequest cleaned = exchange.getRequest().mutate()
                    .headers(h -> h.remove(HttpConstants.HEADER_USER_ID))
                    .build();
            return chain.filter(exchange.mutate().request(cleaned).build());
        }

        // ===== 第 3 步：取出 Authorization 头，格式必须为 "Bearer <token>" =====
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return deny(exchange, ErrorCode.UNAUTHORIZED, "请先登录");
        }
        String token = authorization.substring(7);

        // ===== 第 4 步：验签 + 解析 userId =====
        Long userId;
        try {
            userId = jwtUtil.parseUserId(token);
        } catch (Exception e) {
            // 签名不合法 / 已过期等所有 JWT 异常统一按 401 处理
            log.info("token 校验失败: {}", e.getMessage());
            return deny(exchange, ErrorCode.TOKEN_INVALID, null);
        }

        // ===== 第 5 步：把用户身份透传给下游服务 =====
        // 先 remove 再 add：防止外部伪造 X-User-Id 头（多值头会取到第一个，必须是网关写入的）
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(h -> h.remove(HttpConstants.HEADER_USER_ID))
                .header(HttpConstants.HEADER_USER_ID, String.valueOf(userId))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 拒绝请求：写入 HTTP 状态码 + 统一 Result JSON 响应体。
     */
    private Mono<Void> deny(ServerWebExchange exchange, ErrorCode errorCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            Result<Void> body = (message == null)
                    ? Result.fail(errorCode)
                    : Result.fail(errorCode, message);
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            // 序列化几乎不可能失败，兜底直接 401 空响应
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }
    }

    /** 数字越小优先级越高；-100 保证先于所有路由过滤器执行 */
    @Override
    public int getOrder() {
        return -100;
    }
}
