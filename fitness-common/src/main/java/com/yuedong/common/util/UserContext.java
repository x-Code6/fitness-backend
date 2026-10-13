package com.yuedong.common.util;

/**
 * 当前登录用户上下文（基于 ThreadLocal）。
 *
 * <p>链路：网关校验 JWT 后把 userId 塞进 {@code X-User-Id} 请求头 →
 * 各服务的拦截器读取该头写入 UserContext → 同一次请求内的任何代码
 * （Service/Mapper 等）通过 {@link #getUserId()} 取当前用户，无需层层传参。</p>
 *
 * <p>⚠️ 必须在请求结束后调用 {@link #clear()}（拦截器 afterCompletion），
 * 否则 Tomcat 线程池复用线程时会把上一个用户的 id 泄漏给下一个请求。</p>
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    /** 写入当前用户 id（由各服务的拦截器调用） */
    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    /** 读取当前用户 id；未登录场景返回 null，由业务决定是否抛未登录异常 */
    public static Long getUserId() {
        return USER_ID.get();
    }

    /** 请求结束后清理，防止线程复用导致的数据串号 */
    public static void clear() {
        USER_ID.remove();
    }
}
