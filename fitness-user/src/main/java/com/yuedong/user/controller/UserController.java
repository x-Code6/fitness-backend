package com.yuedong.user.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.user.model.dto.RegisterDTO;
import com.yuedong.user.model.dto.UserUpdateDTO;
import com.yuedong.user.service.UserService;
import com.yuedong.user.model.vo.ProfileVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户资料接口（需登录：网关校验 JWT 后透传 X-User-Id，拦截器写入 UserContext）。
 *
 * <p>网关路由：/api/user/** → 本服务 /user/**。</p>
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Validated // 让 @RequestParam 上的校验注解生效（方法级校验需要在类上标注 @Validated）
public class UserController {

    private final UserService userService;

    /**
     * 注册（免登录白名单接口；注册成功后前端自动调用 /auth/login 换 token）。
     *
     * @param dto 手机号 + 密码 + 可选昵称
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.ok();
    }

    /**
     * 查询当前登录用户的资料。
     *
     * <p>userId 不由前端传入，而是从网关透传的请求头解析而来——
     * 前端无法伪造"查别人资料"。</p>
     */
    @GetMapping("/profile")
    public Result<ProfileVO> profile() {
        return Result.ok(userService.getProfile(UserContext.getUserId()));
    }

    /**
     * 修改个人资料：昵称 / 头像，传哪个改哪个。
     *
     * <p>头像走上传接口拿到路径后再调这里保存，图片本体不经过本接口。</p>
     */
    @PutMapping("/profile")
    public Result<ProfileVO> updateProfile(@Valid @RequestBody UserUpdateDTO dto) {
        return Result.ok(userService.updateProfile(
                UserContext.getUserId(), dto.getNickname(), dto.getAvatarImage()));
    }
}
