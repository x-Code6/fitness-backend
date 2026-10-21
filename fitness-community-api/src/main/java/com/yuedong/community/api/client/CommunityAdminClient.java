package com.yuedong.community.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.community.api.dto.CommunityAdminDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 管理端社区审核契约（只给 fitness-admin-server 的 BFF 调用）。
 *
 * <p>路径统一在 {@code /inner/community/**}：网关会把 {@code /inner/**} 拦掉，
 * 所以这些接口只能从服务内网通过 Feign 到达，不暴露给浏览器。</p>
 */
@FeignClient(name = "fitness-community", path = "/inner/community")
public interface CommunityAdminClient {

    @GetMapping("/post/page")
    Result<PageDTO<CommunityAdminDTO>> page(@RequestParam("keyword") String keyword,
                                            @RequestParam("status") Integer status,
                                            @RequestParam("page") Integer page,
                                            @RequestParam("size") Integer size);

    /** 1 恢复为正常 / 2 审核隐藏 */
    @PostMapping("/post/status")
    Result<Void> setStatus(@RequestParam("postId") Long postId,
                           @RequestParam("status") Integer status);

    /** 管理端删除：软删为 status=0，用户端立即不可见 */
    @PostMapping("/post/delete")
    Result<Void> delete(@RequestParam("postId") Long postId);
}
