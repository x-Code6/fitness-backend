package com.yuedong.user.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.MemberDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import com.yuedong.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户服务内部接口（管理端后端服务 / 其他微服务经 Nacos 直连调用）。
 *
 * <p>安全约定：路径统一带 /inner/ 前缀，网关显式拒绝外部访问（见 AuthGlobalFilter）；
 * 接口签名与 fitness-user-api 的 {@link com.yuedong.user.api.client.UserClient} 一一对应。</p>
 */
@RestController
@RequestMapping("/inner/user")
@RequiredArgsConstructor
public class InnerUserController {

    private final UserService userService;

    /** 批量查用户简况（昵称/头像色），供排行榜、Feed 流展示 */
    @PostMapping("/batch")
    public Result<List<UserBriefDTO>> batchQuery(@Valid @RequestBody BatchUserQueryDTO dto) {
        return Result.ok(userService.batchQuery(dto.getIds()));
    }

    /** 管理端：会员分页（昵称/手机号搜索）；积分数据由管理端后端聚合 point 服务 */
    @GetMapping("/admin/page")
    public Result<PageDTO<MemberDTO>> adminPage(@RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(userService.adminPage(keyword, page, size));
    }

    /** 管理端：禁用/启用会员（对应 t_user.status） */
    @PostMapping("/admin/status")
    public Result<Void> adminSetStatus(@RequestParam Long id, @RequestParam Integer status) {
        userService.adminSetStatus(id, status);
        return Result.ok();
    }
}
