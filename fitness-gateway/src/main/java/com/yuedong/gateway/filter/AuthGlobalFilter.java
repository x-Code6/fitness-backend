package com.yuedong.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.HttpConstants;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.JwtPayload;
import com.yuedong.common.util.JwtUtil;
import com.yuedong.gateway.properties.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 全局鉴权过滤器（Ordered = 最高优先级，最先执行）。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>禁止外部访问服务间内部接口（/inner/**，只允许服务间经 Nacos 直连）；</li>
 *   <li>白名单放行（登录/注册/浏览类只读接口/埋点上报/接口文档）；</li>
 *   <li>校验 JWT 签名与有效期，取出 userId 与 jti；</li>
 *   <li>★校验 jti 是否仍在 Redis 令牌白名单里——这是"禁用用户立即生效"的依据，
 *       详见 {@link AuthProperties} 里 tokenCheckEnabled 的说明；</li>
 *   <li>验签通过后把 userId 写入 X-User-Id 透传给下游服务：下游不再解析 JWT，只读这个头。
 *       外部无法穿透网关伪造它，因为这里会先 remove 再写。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /**
     * Ant 风格路径匹配器。
     *
     * <p>★这里以前用的是 {@code String::startsWith}，而配置里写的是 {@code /api/admin/**} 这种
     * glob 语法——glob 里的 {@code *} 不是字面字符，用前缀比较永远匹配不上，
     * 于是管理端连登录接口都要用户侧 JWT，后台 100% 进不去。
     * 换成 AntPathMatcher 后，配置里的通配符才真正生效。</p>
     *
     * <p>副作用要知道：像 {@code /api/behavior} 这种不带通配符的条目，现在只精确匹配它自己，
     * 不再"顺带"放行 {@code /api/behavior/report}。所以白名单必须写成 {@code /api/behavior/**}
     * 这种显式形式（见 application.yml）。</p>
     */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final AuthProperties authProperties;
    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // ===== 第 1 步：内部接口一律拒绝外部访问 =====
        // 服务间调用走 Nacos 直连（不经过网关），/inner/** 出现在网关说明是外部请求。
        // 这是"没权限"而不是"没登录"，所以回 403
        if (path.contains("/inner/")) {
            return deny(exchange, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "内部接口禁止外部访问");
        }

        // ===== 第 2 步：白名单直接放行 =====
        if (isWhitelisted(path)) {
            // 清掉外部可能伪造的 X-User-Id，防止冒充
            ServerHttpRequest cleaned = exchange.getRequest().mutate()
                    .headers(h -> h.remove(HttpConstants.HEADER_USER_ID))
                    .build();
            return chain.filter(exchange.mutate().request(cleaned).build());
        }

        // ===== 第 3 步：取出 Authorization 头，格式必须为 "Bearer <token>" =====
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return deny(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "请先登录");
        }
        String token = authorization.substring(7);

        // ===== 第 4 步：验签并解析 userId + jti =====
        JwtPayload payload;
        try {
            payload = jwtUtil.parse(token);
        } catch (Exception e) {
            // 签名不合法 / 已过期 / 缺 jti 都按未登录处理，具体原因只写日志不外泄
            log.info("token 校验失败: path={}, reason={}", path, e.getMessage());
            return deny(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_INVALID, null);
        }

        // 先 remove 再 add：防止外部伪造 X-User-Id 头（多值头会取到第一个，必须是网关写入的）
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(h -> h.remove(HttpConstants.HEADER_USER_ID))
                .header(HttpConstants.HEADER_USER_ID, String.valueOf(payload.getId()))
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();

        // ===== 第 5 步：令牌白名单校验（可用 auth.token-check-enabled 关闭，见配置类说明）=====
        if (!authProperties.isTokenCheckEnabled()) {
            return chain.filter(mutatedExchange);
        }

        // 用响应式模板查 EXISTS：返回 Mono<Boolean>。
        // 这里必须走响应式链路而不是 .block()——网关跑在 Netty 事件循环线程上，
        // 阻塞它会占住 I/O 线程，并发一高整个网关停摆
        return redisTemplate.hasKey(RedisKeyConstants.loginToken(payload.getJti()))
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(alive -> {
                    if (Boolean.TRUE.equals(alive)) {
                        return chain.filter(mutatedExchange);
                    }
                    // 令牌已被吊销（禁用/登出）或 Redis 里没有这条记录：fail-close，当未登录处理
                    log.info("令牌不在白名单，已吊销或未登记: userId={}, jti={}", payload.getId(), payload.getJti());
                    return deny(mutatedExchange, HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_INVALID, null);
                });
    }

    /** 白名单匹配：Ant 风格，支持 {@code /api/admin/**} 与 {@code /**}{@code /v3/api-docs} 这类写法 */
    private boolean isWhitelisted(String path) {
        return authProperties.getWhitelist().stream()
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    /**
     * 拒绝请求：写入 HTTP 状态码 + 统一 Result JSON 响应体。
     *
     * <p>★状态码要和业务码对齐：以前这里不分情况一律 401，导致 403（越权访问 /inner）
     * 也返回 401。前端与监控靠 HTTP 状态码区分"该重新登录"和"你没这个权限"，
     * 混在一起会让用户被反复踢回登录页。</p>
     *
     * <p>★响应体只能用 {@code writeWith} 写一次：不能再叠加 {@code setComplete}，
     * 否则等于告诉 Reactor "这个响应结束两次"，表现就是偶发的连接重置或 body 丢失。</p>
     */
    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Result 的字段都是基础类型与字符串，序列化几乎不可能失败；
        // 真失败时退化为手拼的最小 JSON，保证前端拿到的仍是合法信封
        byte[] bytes;
        try {
            Result<Void> body = (message == null)
                    ? Result.fail(errorCode)
                    : Result.fail(errorCode, message);
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            log.warn("拒绝响应序列化失败，退化为最小信封: {}", e.toString());
            bytes = ("{\"code\":" + errorCode.getCode() + ",\"message\":\"unauthorized\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        // 把字节包成 Netty 直接缓冲（零拷贝包装，不额外分配），写一次即结束
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** 数字越小优先级越高；-100 保证先于所有路由过滤器执行 */
    @Override
    public int getOrder() {
        return -100;
    }
}
