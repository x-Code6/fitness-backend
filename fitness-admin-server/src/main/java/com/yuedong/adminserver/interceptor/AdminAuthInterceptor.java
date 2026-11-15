package com.yuedong.adminserver.interceptor;

import com.yuedong.adminserver.annotation.RequireRole;
import com.yuedong.adminserver.auth.JwtService;
import com.yuedong.adminserver.context.AdminContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;


/**
 * 管理端认证 + RBAC 拦截器。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>白名单（验证码/登录）直接放行；</li>
 *   <li>其余请求必须携带本服务签发的 Bearer token，验签解析 adminId/role；</li>
 *   <li>目标方法/类上标注 @RequireRole 时校验角色（SUPER 恒通过）；</li>
 *   <li>通过后写入 AdminContext 供业务与操作日志切面使用。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ===== 第 1 步：读 token（OPTIONS 预检直接放行） =====
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(response, 401, "请先登录");
        }

        // ===== 第 2 步：验签解析 =====
        io.jsonwebtoken.Claims claims;
        try {
            claims = jwtService.parse(authorization.substring(7));
        } catch (Exception e) {
            return reject(response, 401, "登录已过期，请重新登录");
        }
        Long adminId = Long.valueOf(claims.getSubject());
        String role = String.valueOf(claims.get("role"));

        // ===== 第 3 步：RBAC 角色校验（@RequireRole） =====
        if (handler instanceof HandlerMethod handlerMethod) {
            RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
            if (requireRole == null) {
                requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
            }
            if (requireRole != null && !"SUPER".equals(role)) {
                boolean allowed = false;
                for (String r : requireRole.value()) {
                    if (r.equals(role)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    return reject(response, 403, "当前角色无权执行该操作");
                }
            }
        }

        // ===== 第 4 步：写入管理员上下文 =====
        AdminContext.set(adminId, role.equals("SUPER") ? "管理员" : "运营", role);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AdminContext.clear();
    }

    private boolean reject(HttpServletResponse response, int status, String message) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"code\":" + (status == 403 ? 40300 : 40100)
                    + ",\"message\":\"" + message + "\",\"data\":null}");
        } catch (Exception ignore) {
        }
        return false;
    }
}
