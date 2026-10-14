package com.yuedong.community.service;

import com.yuedong.community.model.dto.CommentDTO;
import com.yuedong.community.model.dto.PublishDTO;
import com.yuedong.community.model.vo.CollectResultVO;
import com.yuedong.community.model.vo.CommentPageVO;
import com.yuedong.community.model.vo.CommentVO;
import com.yuedong.community.model.vo.FeedResultVO;
import com.yuedong.community.model.vo.LikeResultVO;
import com.yuedong.community.model.vo.PostVO;

/**
 * 社区业务接口（动态 / 互动 / 评论 / 分发）。
 *
 * <p>★两条全局约定：</p>
 * <ul>
 *   <li><b>分页只收 cursor、只回 nextCursor</b>（docs 4.12 K2）。时间线类游标是动态 id，
 *       推荐流是 {@code 分数_id}。游标对前端不透明，前端不要解析它。</li>
 *   <li><b>userId 可为 null</b>（网关白名单放行的游客）。null 时 liked/collected/mine 一律 false、
 *       需要登录的流返回空列表 + 引导文案，<b>不抛"未登录"</b>——游客看列表是产品行为。</li>
 * </ul>
 */
public interface CommunityService {

    /**
     * Feed 流一页。
     *
     * @param tab    recommend 推荐（默认）/ follow 我关注的人 / friend 互关好友 / mine 我发布的
     * @param cursor 上一页返回的 nextCursor，首页传 null
     * @param size   每页几条，服务层夹在 1~20
     */
    FeedResultVO feed(String tab, String cursor, Integer size, Long userId);

    /** 某个作者的主页动态（他人只能看到他的 PUBLIC，互关好友多看一档 FRIEND，自己看含 PRIVATE） */
    FeedResultVO authorPosts(Long authorId, String cursor, Integer size, Long userId);

    /** 我的-点赞列表（游标是"点赞记录 id"，★不是动态 id：按点赞时间倒序才是这个 tab 的语义） */
    FeedResultVO myLikes(String cursor, Integer size, Long userId);

    /** 我的-收藏列表 */
    FeedResultVO myCollects(String cursor, Integer size, Long userId);

    /** 动态详情。<b>会记一次浏览</b>（登录用户，一人一帖一天一次） */
    PostVO detail(Long postId, Long userId);

    /** 发布动态：昵称/头像走快照，PUBLIC 才扇出到粉丝收件箱 */
    PostVO publish(Long userId, PublishDTO dto);

    /** 删除自己的动态（软删） */
    void deletePost(Long userId, Long postId);

    /** 点赞/取消点赞（幂等切换） */
    LikeResultVO like(Long userId, Long postId);

    /** 收藏/取消收藏（幂等切换） */
    CollectResultVO collect(Long userId, Long postId);

    /** 一级评论一页，每条带前几条回复 + 回复总数（要展开再用 {@link #replies}） */
    CommentPageVO comments(Long postId, String cursor, Integer size, Long userId);

    /** 某个一级评论下的回复一页（id 升序，越早越前） */
    CommentPageVO replies(Long rootId, String cursor, Integer size, Long userId);

    /** 发表评论/回复 */
    CommentVO addComment(Long userId, CommentDTO dto);

    /** 删除自己发的评论（软删，保留占位不让整串回复失去父节点） */
    void deleteComment(Long userId, Long commentId);
}
