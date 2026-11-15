package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.model.dto.LoginDTO;
import com.yuedong.adminserver.model.vo.AdminLoginVO;
import com.yuedong.adminserver.model.vo.CaptchaVO;
import com.yuedong.adminserver.service.AdminAuthService;
import com.yuedong.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端认证入口：图形验证码 + 账号密码登录（BCrypt + RBAC 角色）。
 */
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AuthAdminController {

    private final AdminAuthService adminAuthService;

    /** 图形验证码：captchaId + base64 图片；答案存 Redis（2 分钟一次一用） */
    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        return adminAuthService.captcha();
    }

    /** 登录：验证码校验（一次一用）→ BCrypt 密码比对 → 签发管理端 JWT */
    @PostMapping("/login")
    public Result<AdminLoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return adminAuthService.login(dto);
    }
}
