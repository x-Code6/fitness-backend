package com.yuedong.adminserver.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理员实体（PO），对应表 yd_admin.t_admin。 */
@Data
@TableName("t_admin")
public class Admin {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录账号（唯一） */
    @TableField("username")
    private String username;

    /** 密码的 BCrypt 散列 */
    @TableField("password")
    private String password;

    /** 管理员姓名（顶栏展示） */
    @TableField("real_name")
    private String realName;

    /** SUPER 超管 / OPERATOR 运营 */
    @TableField("role")
    private String role;

    /** 1 正常 / 0 禁用 */
    @TableField("status")
    private Integer status;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
