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
    /**
     * 角色 → 可见菜单。
     *
     * <p>★切片 L 把 "coaches" 和 "reservations" 两个 key 加回来了：
     * 上一轮因为预约模型没落地，核销工作台被摘掉（留一个点开就报错的入口比没有更糟），
     * 现在教练与时段两条接口都已就位。</p>
     *
     * <p>★给 OPERATOR 开放教练与预约、但不开放门店/会员/日志：
     * 前者是日常运营要动的内容资源，后者涉及个人信息与审计痕迹，只给超管。
     * 权限点的粒度是<b>菜单</b>而不是按钮，一期不做按钮级（那就需要一套操作字典表，
     * 而这里没有一个场景真的需要"能看不能改"）。</p>
     */
    private static final Map<String, List<String>> ROLE_MENUS = Map.of(
            "SUPER", List.of("dashboard", "courses", "coaches", "stores", "seckill",
                    "banners", "coupons", "orders", "reservations", "members", "gifts", "logs"),
            "OPERATOR", List.of("dashboard", "courses", "coaches", "seckill",
                    "banners", "coupons", "orders", "reservations", "gifts"));

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
