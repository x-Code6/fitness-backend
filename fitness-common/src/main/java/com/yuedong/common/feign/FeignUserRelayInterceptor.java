package com.yuedong.common.feign;

import com.yuedong.common.constant.HttpConstants;
import com.yuedong.common.util.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Feign 用户信息透传拦截器（统一模块版）。
 *
 * <p>作用：服务间 Feign 调用时，把当前登录用户的身份"接力"到下游：</p>
 * <ol>
 *   <li>X-User-Id ← ThreadLocal 里的用户 id（下游服务的
 *       {@code UserContextInterceptor} 会读这个头继续存入它自己的 ThreadLocal，
 *       用户身份就在整条调用链上传递了）；</li>
 *   <li>Authorization ← 原样透传当前请求的 token（下游如需重新验签可用）。</li>
 * </ol>
 *
 * <p>Spring Cloud OpenFeign 会自动收集容器里所有 RequestInterceptor Bean，
 * 应用到全部 Feign 客户端——所以各服务无需任何额外配置。</p>
 *
 * <p>@ConditionalOnClass：user 服务没有引入 OpenFeign，按类名条件跳过装配，
 * 避免 NoClassDefFoundError。</p>
 */
@Component
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class FeignUserRelayInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        // ===== 第 1 步：ThreadLocal 里的用户 id → X-User-Id 头 =====
        Long userId = UserContext.getUserId();
        if (userId != null) {
            template.header(HttpConstants.HEADER_USER_ID, String.valueOf(userId));
        }

        // ===== 第 2 步：透传当前 HTTP 请求的 Authorization 头 =====
        // （Feign 调用发生在请求线程内，RequestContextHolder 能拿到原始请求）
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader("Authorization");
            if (authorization != null && !authorization.isBlank()) {
                template.header("Authorization", authorization);
            }
        }
        // 定时任务/MQ 消费线程里没有请求上下文：两个头都不带，属于"系统调用"，下游按匿名处理
    }
}
