package com.yuedong.common.web;

import com.yuedong.common.util.UserContext;
import com.yuedong.common.constant.HttpConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 用户上下文拦截器（common 统一版，所有业务服务共用）。
 *
 * <p>链路：网关校验 JWT 后把 userId 放进 X-User-Id 头 →
 * 本拦截器读取该头写入 ThreadLocal（UserContext）→
 * 同请求内的任意代码通过 UserContext.getUserId() 取当前用户。</p>
 *
 * <p>由 CommonWebConfig 统一注册到每个业务服务；
 * 免登录请求没有该头，拦截器什么都不做、直接放行。</p>
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ===== 第 1 步：读取网关透传的用户 id 头 =====
        String userId = request.getHeader(HttpConstants.HEADER_USER_ID);
        // ===== 第 2 步：有则写入 ThreadLocal（匿名请求没有该头，保持空） =====
        if (userId != null && !userId.isBlank()) {
            UserContext.set(Long.valueOf(userId));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // ===== 第 3 步：请求结束必须清理（Tomcat 线程复用，防串号） =====
        UserContext.clear();
    }
}
