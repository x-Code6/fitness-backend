package com.yuedong.user.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.user.model.dto.UserProfileSaveDTO;
import com.yuedong.user.model.vo.UserProfileVO;
import com.yuedong.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健身档案接口（MySQL，需登录）。
 *
 * <p>路径挂在 {@code /body/profile} 而不是 {@code /user/profile}，是为了让
 * "身体数据"这一族接口在网关里只用一条 {@code /api/body/**} 路由就够，
 * 不用为了一个页面去改两条路由。</p>
 */
@RestController
@RequestMapping("/body/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    /**
     * 取档案。
     * <p>★从没填过也返回一个可用结构（目标为 null、跟踪项=全集），
     * 这样前端不需要写"没有档案"这个分支——少一个分支就少一处会错的地方。</p>
     */
    @GetMapping
    public Result<UserProfileVO> get() {
        return Result.ok(userProfileService.get(UserContext.getUserId()));
    }

    /** 存档案。★"传了才改"，只填了目标也不会把身高清掉 */
    @PostMapping
    public Result<UserProfileVO> save(@Valid @RequestBody UserProfileSaveDTO dto) {
        return Result.ok(userProfileService.save(UserContext.getUserId(), dto));
    }
}
