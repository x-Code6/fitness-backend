package com.yuedong.adminserver.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.adminserver.auth.JwtService;
import com.yuedong.adminserver.mapper.AdminMapper;
import com.yuedong.adminserver.model.dto.LoginDTO;
import com.yuedong.adminserver.model.entity.Admin;
import com.yuedong.adminserver.model.vo.AdminLoginVO;
import com.yuedong.adminserver.model.vo.CaptchaResultVO;
import com.yuedong.adminserver.model.vo.CaptchaVO;
import com.yuedong.adminserver.service.AdminAuthService;
import com.yuedong.adminserver.util.CaptchaGenerator;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端认证实现。
 */
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(2);

    /**
     * 角色 → 可见菜单。
     *
     * <p>给 OPERATOR 开放教练与预约、但不开放门店/会员/日志：
     * 前者是日常运营要动的内容资源，后者涉及个人信息与审计痕迹，只给超管。</p>
     */
    private static final Map<String, List<String>> ROLE_MENUS = Map.of(
            "SUPER", List.of("dashboard", "courses", "coaches", "stores", "seckill",
                    "banners", "coupons", "orders", "reservations", "members", "community", "gifts", "logs"),
            "OPERATOR", List.of("dashboard", "courses", "coaches", "seckill",
                    "banners", "coupons", "orders", "reservations", "community", "gifts"));

    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final StringRedisTemplate redis;

    @Override
    public Result<CaptchaVO> captcha() {
        CaptchaResultVO captcha = CaptchaGenerator.generate();
        String captchaId = UUID.randomUUID().toString();
        redis.opsForValue().set(RedisKeyConstants.adminCaptcha(captchaId), captcha.code(), CAPTCHA_TTL);
        return Result.ok(CaptchaVO.builder()
                .captchaId(captchaId)
                .image(captcha.imageBase64())
                .build());
    }

    @Override
    public Result<AdminLoginVO> login(LoginDTO dto) {
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
