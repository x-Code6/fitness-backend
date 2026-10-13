package com.yuedong.community.service.impl;

import com.yuedong.common.constant.RedisKeyConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.UserBriefDTO;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.community.mapper.CommentMapper;
import com.yuedong.community.mapper.PostLikeMapper;
import com.yuedong.community.mapper.PostMapper;
import com.yuedong.community.model.dto.CommentDTO;
import com.yuedong.community.model.dto.PublishDTO;
import com.yuedong.community.model.entity.Comment;
import com.yuedong.community.model.entity.Post;
import com.yuedong.community.model.entity.PostLike;
import com.yuedong.community.model.vo.CommentVO;
import com.yuedong.community.model.vo.FeedResultVO;
import com.yuedong.community.model.vo.LikeResultVO;
import com.yuedong.community.model.vo.PostVO;
import com.yuedong.community.mq.NotifyPublisher;
import com.yuedong.community.service.CommunityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 社区业务实现：动态 / 点赞 / 两级评论。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityServiceImpl implements CommunityService {

    private static final int PAGE_SIZE = 5;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final CommentMapper commentMapper;
    private final UserClient userClient;
    private final NotifyPublisher notifyPublisher;
    private final com.yuedong.community.service.FollowService followService;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;


    // =====================================================================
    // Feed 流：tab=all 走 DB 分页；tab=follow 走推模式收件箱（Redis ZSet）
    // =====================================================================
    @Override
    public FeedResultVO feed(int page, Long userId, String tab) {
        if ("follow".equals(tab)) {
            return followFeed(page, userId);
        }
        return allFeed(page, userId);
    }

    /** 全部动态：DB 按时间倒序分页 */
    private FeedResultVO allFeed(int page, Long userId) {
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .orderByDesc(Post::getId)
                .last("LIMIT " + PAGE_SIZE + " OFFSET " + (long) (page - 1) * PAGE_SIZE));

        // 当前用户的点赞集合（一次查出，内存标记 liked）
        Set<Long> likedIds = likedPostIds(userId, posts.stream().map(Post::getId).toList());

        List<PostVO> list = posts.stream()
                .map(p -> toVO(p, userId, likedIds))
                .toList();
        boolean hasMore = (long) page * PAGE_SIZE < countNormal();
        return FeedResultVO.builder().list(list).hasMore(hasMore).page(page).build();
    }

    /**
     * 关注流（推模式）：发动态时已把 postId ZADD 进每个粉丝的收件箱
     * （feed:inbox:{粉丝id}，score=发布时间），这里按收件箱倒序取 id、回表组装。
     */
    private FeedResultVO followFeed(int page, Long userId) {
        if (userId == null) {
            return FeedResultVO.builder().list(List.of()).hasMore(false).page(page).build();
        }
        long start = (long) (page - 1) * PAGE_SIZE;
        Set<String> ids = redis.opsForZSet().reverseRange(RedisKeyConstants.feedInbox(userId), start, start + PAGE_SIZE - 1);
        if (ids == null || ids.isEmpty()) {
            return FeedResultVO.builder().list(List.of()).hasMore(false).page(page).build();
        }
        List<Long> postIds = ids.stream().map(Long::valueOf).toList();
        Map<Long, Post> postMap = postMapper.selectList(new LambdaQueryWrapper<Post>()
                        .in(Post::getId, postIds)).stream()
                .collect(HashMap::new, (m, p) -> m.put(p.getId(), p), HashMap::putAll);

        Set<Long> likedIds = likedPostIds(userId, postIds);
        // 保持收件箱的时间序（ZSet 已按 score 倒序返回）
        List<PostVO> list = postIds.stream()
                .filter(id -> postMap.containsKey(id) && postMap.get(id).getStatus() == 1)
                .map(id -> toVO(postMap.get(id), userId, likedIds))
                .toList();
        // 还有更多：收件箱里的总数比当前取到的多一页
        Long inboxTotal = redis.opsForZSet().zCard(RedisKeyConstants.feedInbox(userId));
        boolean hasMore = inboxTotal != null && inboxTotal > start + PAGE_SIZE;
        return FeedResultVO.builder().list(list).hasMore(hasMore).page(page).build();
    }

    // =====================================================================
    // 发布动态：昵称/头像从用户服务快照（Feign 批量接口取 1 个）
    // =====================================================================
    @Override
    public PostVO publish(Long userId, PublishDTO dto) {
        UserBriefDTO user = fetchUser(userId);

        Post post = new Post();
        post.setUserId(userId);
        post.setUserName(user != null ? user.getNickname() : "悦动同学");
        post.setAvatarColor(user != null && user.getAvatarColor() != null ? user.getAvatarColor() : "0");
        post.setContent(dto.getContent().trim());
        post.setImages(""); // 图片上传为 P2（Mock 阶段无配图）
        post.setTopic(dto.getTopic() == null || dto.getTopic().isBlank() ? "打卡日常" : dto.getTopic());
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setStatus(1);
        postMapper.insert(post);

        // ===== Feed 推模式：把动态投递到每个粉丝的收件箱（ZSet，score=发布时间） =====
        // 大 V 百万粉丝场景应改"推拉结合"（活跃粉丝推、僵尸粉拉），见设计文档
        List<Long> followerIds = followService.followerIds(userId);
        double score = System.currentTimeMillis();
        followerIds.forEach(fid -> {
            String inbox = RedisKeyConstants.feedInbox(fid);
            redis.opsForZSet().add(inbox, String.valueOf(post.getId()), score);
            redis.opsForZSet().removeRange(inbox, 0, -1001); // 收件箱只留最近 1000 条
        });

        return toVO(post, userId, Set.of());
    }

    // =====================================================================
    // 点赞：唯一约束保证一人一赞；计数原子增减
    // =====================================================================
    @Override
    public LikeResultVO like(Long userId, Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "动态不存在");
        }

        PostLike existed = postLikeMapper.selectOne(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId)
                .eq(PostLike::getUserId, userId));

        boolean liked;
        if (existed != null) {
            // 已点赞 → 取消点赞
            postLikeMapper.deleteById(existed.getId());
            postMapper.changeLikeCount(postId, -1);
            liked = false;
        } else {
            // 未点赞 → 插入点赞记录（唯一索引兜底并发双击）
            PostLike like = new PostLike();
            like.setPostId(postId);
            like.setUserId(userId);
            like.setCreatedAt(LocalDateTime.now());
            try {
                postLikeMapper.insert(like);
                postMapper.changeLikeCount(postId, +1);
                liked = true;
                // 通知动态作者（自己给自己点赞不发通知）
                if (!post.getUserId().equals(userId)) {
                    notifyPublisher.publishCommunityNotify(post.getUserId(), "收到点赞",
                            post.getUserName() + "，你的动态「" + snippet(post.getContent()) + "」收到了新的点赞。");
                }
            } catch (DuplicateKeyException e) {
                // 并发双击：唯一索引拦截，按"已点赞"返回
                liked = true;
            }
        }
        Post latest = postMapper.selectById(postId);
        return new LikeResultVO(liked, latest.getLikeCount());
    }

    // =====================================================================
    // 评论：两级结构
    // =====================================================================
    @Override
    public List<CommentVO> comments(Long postId) {
        List<Comment> all = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, postId)
                .eq(Comment::getStatus, 1)
                .orderByAsc(Comment::getId));

        // ===== 组装两级：一级评论（parent_id 为空）为节点，回复挂在 replies =====
        List<CommentVO> tops = new ArrayList<>();
        Map<Long, CommentVO> nodeMap = new HashMap<>();
        for (Comment c : all) {
            if (c.getParentId() == null) {
                CommentVO vo = toCommentVO(c);
                vo.setReplies(new ArrayList<>());
                tops.add(vo);
                nodeMap.put(c.getId(), vo);
            }
        }
        // 回复按 parent 归组；支持"回复的回复"归到根评论下
        for (Comment c : all) {
            if (c.getParentId() == null) continue;
            CommentVO parent = nodeMap.get(c.getParentId());
            if (parent == null) continue; // 被删父评论的回复暂不展示
            parent.getReplies().add(toCommentVO(c));
        }
        return tops;
    }

    @Override
    public CommentVO addComment(Long userId, CommentDTO dto) {
        // ===== 第 1 步：动态必须存在 =====
        Post post = postMapper.selectById(dto.getPostId());
        if (post == null || post.getStatus() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "动态不存在");
        }

        // ===== 第 2 步：回复时校验父评论（同动态下） =====
        String replyToUser = null;
        if (dto.getParentId() != null) {
            Comment parent = commentMapper.selectById(dto.getParentId());
            if (parent == null || !parent.getPostId().equals(dto.getPostId())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "回复的评论不存在");
            }
            replyToUser = parent.getUserName();
        }

        // ===== 第 3 步：昵称快照（Feign）并落库 =====
        UserBriefDTO user = fetchUser(userId);
        Comment comment = new Comment();
        comment.setPostId(dto.getPostId());
        comment.setUserId(userId);
        comment.setUserName(user != null ? user.getNickname() : "悦动同学");
        comment.setContent(dto.getContent().trim());
        comment.setParentId(dto.getParentId());
        comment.setReplyToUser(replyToUser);
        comment.setStatus(1);
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);

        // ===== 第 4 步：动态评论数 +1；通知作者（评论自己动态不通知） =====
        postMapper.changeCommentCount(dto.getPostId(), +1);
        if (!post.getUserId().equals(userId)) {
            notifyPublisher.publishCommunityNotify(post.getUserId(), "收到新评论",
                    post.getUserName() + "，你的动态「" + snippet(post.getContent()) + "」有新评论："
                            + snippet(comment.getContent()));
        }

        return toCommentVO(comment);
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 当前用户对这批动态的点赞 id 集合 */
    private Set<Long> likedPostIds(Long userId, List<Long> postIds) {
        if (userId == null || postIds.isEmpty()) return Set.of();
        return postLikeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                        .eq(PostLike::getUserId, userId)
                        .in(PostLike::getPostId, postIds))
                .stream().map(PostLike::getPostId).collect(Collectors.toSet());
    }

    /** Feign 取用户昵称/头像（失败降级为默认值，不让社区挂掉） */
    private UserBriefDTO fetchUser(Long userId) {
        try {
            var result = userClient.batch(new BatchUserQueryDTO(List.of(userId)));
            if (result.getCode() == 0 && result.getData() != null && !result.getData().isEmpty()) {
                return result.getData().get(0);
            }
        } catch (Exception e) {
            log.warn("获取用户信息失败，降级默认昵称: userId={}", userId, e);
        }
        UserBriefDTO fallback = new UserBriefDTO();
        fallback.setId(userId);
        fallback.setNickname("悦动同学");
        fallback.setAvatarColor("0");
        return fallback;
    }

    private PostVO toVO(Post p, Long userId, Set<Long> likedIds) {
        return PostVO.builder()
                .id(p.getId())
                .userId(p.getUserId())
                .nickname(p.getUserName())
                .avatarIdx(p.getAvatarColor() == null ? 0 : Integer.parseInt(p.getAvatarColor()))
                .content(p.getContent())
                .images(p.getImages() == null || p.getImages().isBlank()
                        ? List.of() : toIntList(p.getImages()))
                .likes(p.getLikeCount())
                .liked(likedIds.contains(p.getId()))
                .comments(p.getCommentCount())
                .topic(p.getTopic())
                .time(relativeTime(p.getCreatedAt()))
                .mine(userId != null && p.getUserId().equals(userId))
                .build();
    }

    private CommentVO toCommentVO(Comment c) {
        return CommentVO.builder()
                .id(c.getId())
                .userId(c.getUserId())
                .userName(c.getUserName())
                .content(c.getContent())
                .replyToUser(c.getReplyToUser())
                .time(relativeTime(c.getCreatedAt()))
                .build();
    }

    /** 相对时间文案（与原 Mock 表现一致） */
    private String relativeTime(LocalDateTime time) {
        Duration d = Duration.between(time, LocalDateTime.now());
        if (d.toMinutes() < 1) return "刚刚";
        if (d.toHours() < 1) return d.toMinutes() + " 分钟前";
        if (d.toHours() < 24) return d.toHours() + " 小时前";
        if (d.toDays() == 1) return "昨天 " + time.format(DateTimeFormatter.ofPattern("HH:mm"));
        return time.format(TIME_FMT);
    }

    private List<Integer> toIntList(String csv) {
        List<Integer> result = new ArrayList<>();
        for (String s : csv.split(",")) {
            try {
                result.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    private String snippet(String text) {
        return text.length() > 20 ? text.substring(0, 20) + "…" : text;
    }

    private long countNormal() {
        return postMapper.selectCount(new LambdaQueryWrapper<Post>().eq(Post::getStatus, 1));
    }
}
