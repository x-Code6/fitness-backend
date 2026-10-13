package com.yuedong.community.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.community.model.dto.CommentDTO;
import com.yuedong.community.model.dto.PublishDTO;
import com.yuedong.community.model.vo.CommentVO;
import com.yuedong.community.model.vo.FeedResultVO;
import com.yuedong.community.model.vo.LikeResultVO;
import com.yuedong.community.model.vo.PostVO;
import com.yuedong.community.service.CommunityService;
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
 * 社区接口（feed 免登录浏览；发布/点赞/评论需登录）。
 *
 * <p>网关路由：/api/community/** → 本服务 /community/**。</p>
 */
@RestController
@RequestMapping("/community")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;

    /**
     * Feed 流分页（匿名可浏览；登录后返回 liked/mine 标记）。
     *
     * @param tab all=全部动态 / follow=关注的人的动态
     */
    @GetMapping("/feed")
    public Result<FeedResultVO> feed(@RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "all") String tab) {
        return Result.ok(communityService.feed(page, UserContext.getUserId(), tab));
    }

    /** 发布动态（需登录；成功后 +20 积分由 point 服务异步入账） */
    @PostMapping("/publish")
    public Result<PostVO> publish(@Valid @RequestBody PublishDTO dto) {
        return Result.ok(communityService.publish(UserContext.getUserId(), dto));
    }

    /** 点赞/取消点赞 */
    @PostMapping("/like")
    public Result<LikeResultVO> like(@RequestParam Long postId) {
        return Result.ok(communityService.like(UserContext.getUserId(), postId));
    }

    /** 动态评论列表（两级结构） */
    @GetMapping("/comments")
    public Result<List<CommentVO>> comments(@RequestParam Long postId) {
        return Result.ok(communityService.comments(postId));
    }

    /** 发表评论/回复 */
    @PostMapping("/comment")
    public Result<CommentVO> comment(@Valid @RequestBody CommentDTO dto) {
        return Result.ok(communityService.addComment(UserContext.getUserId(), dto));
    }
}
