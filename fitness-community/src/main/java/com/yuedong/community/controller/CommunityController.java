package com.yuedong.community.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.community.model.dto.CommentDTO;
import com.yuedong.community.model.dto.PublishDTO;
import com.yuedong.community.model.vo.CollectResultVO;
import com.yuedong.community.model.vo.CommentPageVO;
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

/**
 * 社区接口。
 *
 * <p>网关路由：{@code /api/community/**} → 本服务 {@code /community/**}（StripPrefix=1）。</p>
 *
 * <p>★白名单只放四条只读口：{@code feed}、{@code detail}、{@code comments}、{@code replies}。
 * 游客能刷列表、能进详情看公开动态、能读评论与回复；<b>发布/点赞/收藏/评论/关注/主页一律要登录</b>
 * ——这些要么写数据、要么按人去重，{@code author} 还额外是一个容易被批量爬的口子。
 * 对应地，服务层在这四条口上都做了 {@code userId == null} 的分支
 * （列表只出 PUBLIC 池、详情遇到 FRIEND/PRIVATE 直接 46004 拒），
 * 所以"网关白名单"和"可见性判定"是两道各管各的闸门，缺一不可。</p>
 *
 * <p>★分页统一 {@code cursor + size}，返回 {@code nextCursor + hasMore}（docs 4.12 K2）。
 * 游标对前端不透明：原样回传即可，不要解析、不要自己拼。</p>
 */
@RestController
@RequestMapping("/community")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;

    /**
     * Feed 流一页。
     *
     * @param tab    recommend 推荐（默认）/ follow 关注 / friend 好友 / mine 我的
     * @param cursor 上一页的 nextCursor，首页不传
     */
    @GetMapping("/feed")
    public Result<FeedResultVO> feed(@RequestParam(defaultValue = "recommend") String tab,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer size) {
        return Result.ok(communityService.feed(tab, cursor, size, UserContext.getUserId()));
    }

    /** 动态详情（登录用户会记一次浏览：一人一帖一天一次） */
    @GetMapping("/detail")
    public Result<PostVO> detail(@RequestParam Long postId) {
        return Result.ok(communityService.detail(postId, UserContext.getUserId()));
    }

    /** 某个用户的主页动态（他人只看 PUBLIC，互关好友多看 FRIEND，自己看含 PRIVATE） */
    @GetMapping("/author")
    public Result<FeedResultVO> authorPosts(@RequestParam Long authorId,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer size) {
        return Result.ok(communityService.authorPosts(authorId, cursor, size, UserContext.getUserId()));
    }

    /** 我的-点赞（按点赞时间倒序，游标是点赞记录 id） */
    @GetMapping("/my/likes")
    public Result<FeedResultVO> myLikes(@RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer size) {
        return Result.ok(communityService.myLikes(cursor, size, UserContext.getUserId()));
    }

    /** 我的-收藏 */
    @GetMapping("/my/collects")
    public Result<FeedResultVO> myCollects(@RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer size) {
        return Result.ok(communityService.myCollects(cursor, size, UserContext.getUserId()));
    }

    /** 发布动态（visibility 不传按 PUBLIC；只有 PUBLIC 会扇出到粉丝收件箱） */
    @PostMapping("/publish")
    public Result<PostVO> publish(@Valid @RequestBody PublishDTO dto) {
        return Result.ok(communityService.publish(UserContext.getUserId(), dto));
    }

    /** 删除自己的动态（软删） */
    @PostMapping("/post/delete")
    public Result<Void> deletePost(@RequestParam Long postId) {
        communityService.deletePost(UserContext.getUserId(), postId);
        return Result.ok();
    }

    /** 点赞/取消点赞（幂等切换） */
    @PostMapping("/like")
    public Result<LikeResultVO> like(@RequestParam Long postId) {
        return Result.ok(communityService.like(UserContext.getUserId(), postId));
    }

    /** 收藏/取消收藏（幂等切换） */
    @PostMapping("/collect")
    public Result<CollectResultVO> collect(@RequestParam Long postId) {
        return Result.ok(communityService.collect(UserContext.getUserId(), postId));
    }

    /** 一级评论一页，每条带前两条回复与回复总数 */
    @GetMapping("/comments")
    public Result<CommentPageVO> comments(@RequestParam Long postId,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer size) {
        return Result.ok(communityService.comments(postId, cursor, size, UserContext.getUserId()));
    }

    /** 展开某个一级评论下的回复（id 升序） */
    @GetMapping("/replies")
    public Result<CommentPageVO> replies(@RequestParam Long rootId,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer size) {
        return Result.ok(communityService.replies(rootId, cursor, size, UserContext.getUserId()));
    }

    /** 发表评论/回复（parentId 传了就是回复；两级封顶，回复的回复会归到同一个一级下） */
    @PostMapping("/comment")
    public Result<CommentVO> comment(@Valid @RequestBody CommentDTO dto) {
        return Result.ok(communityService.addComment(UserContext.getUserId(), dto));
    }

    /** 删除自己的评论（软删，列表里留"该评论已被删除"占位） */
    @PostMapping("/comment/delete")
    public Result<Void> deleteComment(@RequestParam Long commentId) {
        communityService.deleteComment(UserContext.getUserId(), commentId);
        return Result.ok();
    }
}
