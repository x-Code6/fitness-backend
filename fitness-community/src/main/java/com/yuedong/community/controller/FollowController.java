package com.yuedong.community.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.community.model.vo.FollowUserVO;
import com.yuedong.community.service.FollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 关注/粉丝接口（需登录）。
 */
@RestController
@RequestMapping("/community/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /** 关注某人 */
    @PostMapping
    public Result<Void> follow(@RequestParam Long followeeId) {
        followService.follow(UserContext.getUserId(), followeeId);
        return Result.ok();
    }

    /** 取消关注 */
    @PostMapping("/cancel")
    public Result<Void> cancel(@RequestParam Long followeeId) {
        followService.unfollow(UserContext.getUserId(), followeeId);
        return Result.ok();
    }

    /** 是否已关注 */
    @GetMapping("/is")
    public Result<Boolean> isFollow(@RequestParam Long targetId) {
        return Result.ok(followService.isFollowing(UserContext.getUserId(), targetId));
    }

    /** 我关注的人 */
    @GetMapping("/list")
    public Result<List<FollowUserVO>> following() {
        return Result.ok(followService.followingList(UserContext.getUserId()));
    }

    /** 我的粉丝 */
    @GetMapping("/fans")
    public Result<List<FollowUserVO>> fans() {
        return Result.ok(followService.fansList(UserContext.getUserId()));
    }

    /** 我与某用户的共同关注（Redis SINTER） */
    @GetMapping("/common")
    public Result<List<FollowUserVO>> common(@RequestParam Long targetId) {
        return Result.ok(followService.commonFollow(UserContext.getUserId(), targetId));
    }
}
