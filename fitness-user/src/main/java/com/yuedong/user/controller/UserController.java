package com.yuedong.user.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.user.model.dto.RegisterDTO;
import com.yuedong.user.service.UserService;
import com.yuedong.user.model.vo.ProfileVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
     * 修改昵称（用 @RequestParam 接收演示另一种参数形态： PUT + 查询参数）。
     *
     * @param nickname 新昵称（直接在参数上加校验注解，需配合 @Validated 生效）
     * @return 更新后的资料
     */
    @PutMapping("/profile")
    public Result<ProfileVO> updateNickname(@RequestParam @NotBlank(message = "昵称不能为空")
                                            @Size(max = 20, message = "昵称最长 20 个字符")
                                            String nickname) {
        return Result.ok(userService.updateNickname(UserContext.getUserId(), nickname));
    }
}
