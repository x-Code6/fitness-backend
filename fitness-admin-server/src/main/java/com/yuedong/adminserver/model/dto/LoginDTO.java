package com.yuedong.adminserver.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理端登录请求参数。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 验证码 id（来自 /admin/auth/captcha） */
    @NotBlank(message = "验证码不能为空")
    private String captchaId;

    @NotBlank(message = "验证码不能为空")
    private String captchaCode;
}
