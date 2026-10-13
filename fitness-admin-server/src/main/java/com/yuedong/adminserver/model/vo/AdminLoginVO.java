package com.yuedong.adminserver.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 管理端登录结果视图（token + 身份 + 菜单权限）。
 */
@Data
@Builder
public class AdminLoginVO {

    private String token;

    /** 显示名（管理员姓名） */
    private String name;

    /** SUPER / OPERATOR */
    private String role;

    /** 该角色可见的菜单 key 列表（前端动态渲染侧边栏） */
    private List<String> menus;
}
