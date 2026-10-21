package com.yuedong.user.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（PO），对应表 yd_user.t_user。
 *
 * <p>包结构约定：每个服务的 model 包下分 entity（PO，对应表）/ dto（入参）/ vo（出参）三个子包，
 * PO 跟随表结构、DTO 跟随入参契约、VO 跟随出参契约，三者互不混用。</p>
 *
 * <p>MyBatis-Plus 注解说明：</p>
 * <ul>
 *   <li>@TableName：实体 ↔ 表的映射；</li>
 *   <li>@TableId(type = AUTO)：主键自增，插入后 MP 回填数据库生成的主键；</li>
 *   <li>@TableField：显式声明列名（配合驼峰映射可省略，显式写出可读性更好）。</li>
 * </ul>
 *
 * <p>⚠️ password 存 BCrypt 散列，任何 VO 都不得携带它。</p>
 */
@Data
@TableName("t_user")
public class User {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 手机号（登录账号，唯一索引） */
    @TableField("phone")
    private String phone;

    /** 密码的 BCrypt 散列（60 字符，自带随机盐，不可逆） */
    @TableField("password")
    private String password;

    /** 昵称 */
    @TableField("nickname")
    private String nickname;

    /** 头像配色下标（前端渐变色盘 0~5）；没上传头像时前端用它画渐变 */
    @TableField("avatar_color")
    private String avatarColor;

    /** 头像图片访问路径（/uploads/avatar/xxx.png），空表示用渐变色块 */
    @TableField("avatar_image")
    private String avatarImage;

    /** 状态：1 正常 / 0 禁用 */
    @TableField("status")
    private Integer status;

    /** 注册时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 最后登录时间：登录成功时由服务端写入，管理端会员列表展示用（v2 新增列） */
    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
