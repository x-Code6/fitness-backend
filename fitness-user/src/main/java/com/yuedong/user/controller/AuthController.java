package com.yuedong.user.controller;

import com.yuedong.common.result.Result;
import com.yuedong.user.model.dto.LoginDTO;
import com.yuedong.user.service.UserService;
import com.yuedong.user.model.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（免登录白名单内）。
 *
 * <p>网关路由：/api/auth/** → 本服务 /auth/**（StripPrefix 去掉 /api）。</p>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 账号密码登录。
     *
     * <p>处理流程（对应 Service 实现）：</p>
     * <ol>
     *   <li>@Valid 触发参数校验，失败由全局异常处理器返回提示；</li>
     *   <li>查用户 → BCrypt 比对密码 → 状态校验；</li>
     *   <li>签发 JWT 返回给前端存储。</li>
     * </ol>
     *
     * @param dto 手机号 + 密码
     * @return token + 昵称
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(userService.login(dto));
    }
}
