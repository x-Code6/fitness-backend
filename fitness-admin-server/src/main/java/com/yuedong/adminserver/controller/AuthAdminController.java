package com.yuedong.adminserver.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.adminserver.auth.JwtService;
import com.yuedong.adminserver.model.dto.LoginDTO;
import com.yuedong.adminserver.model.entity.Admin;
import com.yuedong.adminserver.model.vo.AdminLoginVO;
import com.yuedong.adminserver.model.vo.CaptchaVO;
import com.yuedong.adminserver.mapper.AdminMapper;
import com.yuedong.adminserver.util.CaptchaGenerator;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端认证：图形验证码 + 账号密码登录（BCrypt + RBAC 角色）。
 */
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AuthAdminController {

    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(2);

    /** 角色对应的菜单权限（前端动态渲染侧边栏） */
    private static final Map<String, List<String>> ROLE_MENUS = Map.of(
            "SUPER", List.of("dashboard", "courses", "seckill", "orders", "verify",
                    "coupons", "gifts", "members", "stores", "logs"),
            "OPERATOR", List.of("dashboard", "courses", "seckill", "orders", "coupons", "gifts"));

    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final StringRedisTemplate redis;

    /** 图形验证码：captchaId + base64 图片；答案存 Redis（2 分钟一次一用） */
    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        CaptchaGenerator.CaptchaResult captcha = CaptchaGenerator.generate();
        String captchaId = UUID.randomUUID().toString();
        redis.opsForValue().set(RedisKeyConstants.adminCaptcha(captchaId), captcha.code(), CAPTCHA_TTL);
        return Result.ok(CaptchaVO.builder()
                .captchaId(captchaId)
                .image(captcha.imageBase64())
                .build());
    }

    /**
     * 登录：验证码校验（一次一用）→ BCrypt 密码比对 → 签发管理端 JWT。
     */
    @PostMapping("/login")
    public Result<AdminLoginVO> login(@Valid @RequestBody LoginDTO dto) {
        // ===== 第 1 步：校验图形验证码（取出即删，防重放） =====
        String saved = redis.opsForValue().get(RedisKeyConstants.adminCaptcha(dto.getCaptchaId()));
        redis.delete(RedisKeyConstants.adminCaptcha(dto.getCaptchaId()));
        if (saved == null || !saved.equalsIgnoreCase(dto.getCaptchaCode())) {
            return Result.fail(40000, "验证码错误或已过期");
        }

        // ===== 第 2 步：查管理员并比对密码 =====
        Admin admin = adminMapper.selectOne(new LambdaQueryWrapper<Admin>()
                .eq(Admin::getUsername, dto.getUsername()));
        if (admin == null || !passwordEncoder.matches(dto.getPassword(), admin.getPassword())) {
            return Result.fail(41004, "用户名或密码错误");
        }
        if (admin.getStatus() != 1) {
            return Result.fail(41005, "账号已被禁用");
        }

        // ===== 第 3 步：签发 token（含角色），返回菜单权限 =====
        String token = jwtService.generate(admin.getId(), admin.getRole());
        return Result.ok(AdminLoginVO.builder()
                .token(token)
                .name(admin.getRealName())
                .role(admin.getRole())
                .menus(ROLE_MENUS.getOrDefault(admin.getRole(), List.of()))
                .build());
    }
}
