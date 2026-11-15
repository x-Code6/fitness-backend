package com.yuedong.adminserver.service;

import com.yuedong.adminserver.model.dto.LoginDTO;
import com.yuedong.adminserver.model.vo.AdminLoginVO;
import com.yuedong.adminserver.model.vo.CaptchaVO;
import com.yuedong.common.result.Result;

/**
 * 管理端认证业务接口：图形验证码 + 账号密码登录。
 */
public interface AdminAuthService {

    /** 图形验证码：captchaId + base64 图片；答案存 Redis（2 分钟一次一用） */
    Result<CaptchaVO> captcha();

    /** 登录：验证码校验（一次一用）→ BCrypt 密码比对 → 签发管理端 JWT */
    Result<AdminLoginVO> login(LoginDTO dto);
}
