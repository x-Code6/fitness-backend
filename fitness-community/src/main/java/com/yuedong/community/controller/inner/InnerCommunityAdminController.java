package com.yuedong.community.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.community.api.dto.CommunityAdminDTO;
import com.yuedong.community.service.CommunityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端社区审核内部接口（fitness-admin-server BFF 通过 Feign 调用）。
 *
 * <p>路径在 {@code /inner/**} 下，网关会直接拒绝外部访问；
 * 认证与 RBAC 由 admin-server 的 {@code /admin/community/**} 负责。</p>
 */
@RestController
@RequestMapping("/inner/community")
@RequiredArgsConstructor
public class InnerCommunityAdminController {

    private final CommunityService communityService;

    @GetMapping("/post/page")
    public Result<PageDTO<CommunityAdminDTO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(communityService.adminPage(keyword, status, page, size));
    }

    @PostMapping("/post/status")
    public Result<Void> status(@RequestParam Long postId, @RequestParam Integer status) {
        communityService.adminSetStatus(postId, status);
        return Result.ok();
    }

    @PostMapping("/post/delete")
    public Result<Void> delete(@RequestParam Long postId) {
        communityService.adminDelete(postId);
        return Result.ok();
    }
}
