package com.yuedong.adminserver.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色权限注解：标注在需要特定角色的 Controller 方法/类上。
 *
 * <p>用法：{@code @RequireRole("SUPER")}（仅超管）
 * 或 {@code @RequireRole({"SUPER", "OPERATOR"})}。SUPER 角色恒通过。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色列表 */
    String[] value();
}
