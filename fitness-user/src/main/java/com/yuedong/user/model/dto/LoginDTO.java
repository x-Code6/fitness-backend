package com.yuedong.user.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求参数（账号密码模式；后期验证码模式会新增 sms_code 字段并做模式区分）。
 */
@Data
public class LoginDTO {

    /** 手机号 */
    @NotBlank(message = "手机号不能为空")
    private String phone;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    private String password;
}
