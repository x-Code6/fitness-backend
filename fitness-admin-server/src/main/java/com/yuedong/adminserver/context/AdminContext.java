package com.yuedong.adminserver.context;

import com.yuedong.common.util.UserContext;

/**
 * 管理员上下文（ThreadLocal）。
 *
 * <p>与用户端 UserContext 分离：管理端 token 独立签发（含 role 声明），
 * 由 AdminAuthInterceptor 写入，避免两种身份混在一个上下文里。</p>
 */
public class AdminContext {

    private static final ThreadLocal<Long> ADMIN_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ADMIN_NAME = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private AdminContext() {
    }

    public static void set(Long id, String name, String role) {
        ADMIN_ID.set(id);
        ADMIN_NAME.set(name);
        ROLE.set(role);
    }

    public static Long getAdminId() {
        return ADMIN_ID.get();
    }

    public static String getAdminName() {
        return ADMIN_NAME.get();
    }

    public static String getRole() {
        return ROLE.get();
    }

    public static void clear() {
        ADMIN_ID.remove();
        ADMIN_NAME.remove();
        ROLE.remove();
    }
}
