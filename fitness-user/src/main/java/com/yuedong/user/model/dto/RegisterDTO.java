package com.yuedong.user.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数（DTO）。
 *
 * <p>校验注解由 Controller 的 @Valid 触发，失败抛 MethodArgumentNotValidException，
 * 由 common 的全局异常处理器转成"第一条错误提示"返回。</p>
 */
@Data
public class RegisterDTO {

    /** 手机号（同时作为登录账号，后期验证码登录也基于它） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 密码（明文仅出现在请求中，落库前必须 BCrypt 散列） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位之间")
    private String password;

    /** 昵称（可不填，服务端给默认值） */
    @Size(max = 20, message = "昵称最长 20 个字符")
    private String nickname;
}
