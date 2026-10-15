package com.yuedong.user.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.MemberDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务 Feign 契约（业务 + 管理端内部接口）。
 *
 * <p>消费方引入本模块后，在启动类上声明
 * {@code @EnableFeignClients(basePackages = "com.yuedong.user.api")} 按需启用。</p>
 */
@FeignClient(name = "fitness-user", contextId = "userClient")
public interface UserClient {

    /**
     * 批量查用户简况（排行榜/Feed 流/管理端补昵称）。
     * 与 fitness-user 的 {@code POST /inner/user/batch} 对应。
     */
    @PostMapping("/inner/user/batch")
    Result<List<UserBriefDTO>> batch(@RequestBody BatchUserQueryDTO dto);

    /** 管理端：会员分页（昵称/手机号搜索）。与 {@code GET /inner/user/admin/page} 对应。 */
    @GetMapping("/inner/user/admin/page")
    Result<PageDTO<MemberDTO>> memberPage(@RequestParam("keyword") String keyword,
                                          @RequestParam("page") Integer page,
                                          @RequestParam("size") Integer size);

    /** 管理端：禁用/启用会员。与 {@code POST /inner/user/admin/status} 对应。 */
    @PostMapping("/inner/user/admin/status")
    Result<Void> memberStatus(@RequestParam("id") Long id, @RequestParam("status") Integer status);

    /** 管理端看板：正常会员总数。与 {@code GET /inner/user/admin/count} 对应。 */
    @GetMapping("/inner/user/admin/count")
    Result<Long> memberCount();
}
