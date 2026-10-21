package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.community.api.client.CommunityAdminClient;
import com.yuedong.community.api.dto.CommunityAdminDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端社区审核 BFF：社区服务负责数据，本层只做转发与操作日志。
 */
@RestController
@RequestMapping("/admin/community")
@RequiredArgsConstructor
public class CommunityAdminController {

    private final CommunityAdminClient communityAdminClient;

    @GetMapping("/page")
    public Result<PageDTO<CommunityAdminDTO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return communityAdminClient.page(keyword, status, page, size);
    }

    @OperateLog(module = "社区审核", operation = "隐藏/恢复动态")
    @PostMapping("/status")
    public Result<Void> status(@RequestParam Long postId, @RequestParam Integer status) {
        return communityAdminClient.setStatus(postId, status);
    }

    @OperateLog(module = "社区审核", operation = "删除动态")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam Long postId) {
        return communityAdminClient.delete(postId);
    }
}
