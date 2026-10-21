package com.yuedong.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.community.api.dto.CommunityAdminDTO;
import com.yuedong.community.mapper.CommentMapper;
import com.yuedong.community.mapper.PostCollectMapper;
import com.yuedong.community.mapper.PostLikeMapper;
import com.yuedong.community.mapper.PostMapper;
import com.yuedong.community.model.dto.CommentDTO;
import com.yuedong.community.model.dto.PublishDTO;
import com.yuedong.community.model.entity.Comment;
import com.yuedong.community.model.entity.Post;
import com.yuedong.community.model.entity.PostCollect;
import com.yuedong.community.model.entity.PostLike;
import com.yuedong.community.model.vo.CollectResultVO;
import com.yuedong.community.model.vo.CommentPageVO;
import com.yuedong.community.model.vo.CommentVO;
import com.yuedong.community.model.vo.FeedResultVO;
import com.yuedong.community.model.vo.LikeResultVO;
import com.yuedong.community.model.vo.PostScoreRow;
import com.yuedong.community.model.vo.PostVO;
import com.yuedong.community.model.vo.ReplyCountRow;
import com.yuedong.community.mq.NotifyPublisher;
import com.yuedong.community.service.CommunityService;
import com.yuedong.community.service.FollowService;
import com.yuedong.community.service.PostViewService;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 社区业务实现：动态（三档可见性）/ 四种 Feed / 点赞 / 收藏 / 两级评论 / 浏览量。
 *
 * <p>四条拍板在本类的落点（docs 4.12）：</p>
 * <ul>
 *   <li><b>K1 浏览量三层</b> → 全部收在 {@link PostViewService}，本类只负责"读的时候把增量叠上去"；</li>
 *   <li><b>K2 游标分页</b> → 所有列表都是 {@code cursor + size → nextCursor + hasMore}，
 *       没有 page/OFFSET；</li>
 *   <li><b>K3 可见性</b> → 按流分工而不是一个大 OR 查询：推荐流与收件箱只装 PUBLIC，
 *       好友流用拉模式查互关，私密只有作者自己看得到；</li>
 *   <li>扇出（推模式）只在 {@link #publish} 里发生，且<b>只有 PUBLIC 动态会扇出</b>。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityServiceImpl implements CommunityService {

    // ===== 分页：一页几条 =====
    // ★默认 10、上限 20：上限是防止"前端传 size=10000 一次拖库"，
    // 不是为了防攻击——真正的防刷在网关限流那一层，这里只是把接口自身的形状收住
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 20;

    /** 参与推荐的最近天数。★分数是算出来的、没有索引可用，所以必须框窗口（见 PostMapper 注释） */
    private static final int RECOMMEND_RECENT_DAYS = 7;

    /** 收件箱最多扫多少个成员。★和 publish 里"只留最近 1000 条"是同一个数，两处要一起改 */
    private static final int INBOX_SCAN_LIMIT = 1000;

    /** 一级评论默认带几条回复（其余靠"查看全部 N 条回复"展开） */
    private static final int REPLY_PREVIEW_SIZE = 2;

    // ===== 可见性三档，取值与 t_post.visibility 完全一致 =====
    private static final String VIS_PUBLIC = "PUBLIC";
    private static final String VIS_FRIEND = "FRIEND";
    private static final String VIS_PRIVATE = "PRIVATE";

    // ===== 动态状态：0 作者删除 / 1 正常 / 2 管理员隐藏 =====
    // ★用户端所有查询只放行 1；2 是给审核用的“可恢复下架”，和作者删除分开
    private static final int POST_STATUS_DELETED = 0;
    private static final int POST_STATUS_NORMAL = 1;
    private static final int POST_STATUS_HIDDEN = 2;

    // ===== Feed tab =====
    private static final String TAB_RECOMMEND = "recommend";
    private static final String TAB_FOLLOW = "follow";
    private static final String TAB_FRIEND = "friend";
    private static final String TAB_MINE = "mine";

    private static final String DELETED_TEXT = "该评论已被删除";
    private static final String LOGIN_GUIDE = "登录后可以查看关注与好友的动态";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final PostCollectMapper postCollectMapper;
    private final CommentMapper commentMapper;
    private final UserClient userClient;
    private final NotifyPublisher notifyPublisher;
    private final FollowService followService;
    private final PostViewService postViewService;
    private final StringRedisTemplate redis;

    // =====================================================================
    // Feed：四种流四把游标（docs 4.12 K2/K3）
    // =====================================================================
    @Override
    public FeedResultVO feed(String tab, String cursor, Integer size, Long userId) {
        int pageSize = clampSize(size);
        String tabName = tab == null || tab.isBlank() ? TAB_RECOMMEND : tab.trim().toLowerCase();
        switch (tabName) {
            case TAB_FOLLOW:
                return followFeed(pageSize, cursor, userId);
            case TAB_FRIEND:
                return friendFeed(pageSize, cursor, userId);
            case TAB_MINE:
                return mineFeed(pageSize, cursor, userId);
            default:
                // ★未知 tab 退回推荐流而不是抛"参数不正确"：tab 是导航参数，
                // 前端把名字拼错时给一屏内容比给一个红条友好得多
                return recommendFeed(pageSize, cursor, userId);
        }
    }

    /**
     * 推荐流：算分 + {@code score < 游标} 的 keyset 翻页。
     * <p>★只装 PUBLIC（K3）：陌生人流里出现"仅好友"的动态就是隐私事故，
     * 所以 SQL 里写死 {@code visibility = 'PUBLIC'}，不给它进这一流的机会。</p>
     */
    private FeedResultVO recommendFeed(int pageSize, String cursor, Long userId) {
        ScoreCursor pageCursor = decodeScoreCursor(cursor);
        List<PostScoreRow> rows = postMapper.selectRecommendPage(
                pageCursor.getScore(), pageCursor.getPostId(), RECOMMEND_RECENT_DAYS, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<PostScoreRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        if (page.isEmpty()) {
            return emptyFeed("推荐", "今天还没有热门动态，去发一条吧。");
        }

        List<Long> ids = page.stream().map(PostScoreRow::getPostId).toList();
        List<Post> posts = orderByIds(loadByIds(ids), ids);
        PostScoreRow last = page.get(page.size() - 1);
        return FeedResultVO.builder()
                .list(assemble(posts, userId))
                // ★游标里带上分数是为了"下一页从这条分数之后继续"；id 只是并列同分时的次序锚点
                .nextCursor(hasMore ? last.getScore() + "_" + last.getPostId() : null)
                .hasMore(hasMore)
                .tabText("推荐")
                .emptyText(null)
                .build();
    }

    /**
     * 关注流（推模式收件箱）：Redis ZSet 只给"候选 id 集合"，<b>翻页仍交给 DB 的 id 游标</b>。
     *
     * <p>★为什么不在 ZSet 上按 index 翻页（旧的 offset 写法）：
     * 新动态 ZADD 进来会把所有成员往后挤一位，第 2 页就会重复第 1 页的最后一条。
     * 按 id 游标就没这个问题——动态 id 自增，id 倒序就是时间倒序。</p>
     */
    private FeedResultVO followFeed(int pageSize, String cursor, Long userId) {
        if (userId == null) {
            return emptyFeed("关注", LOGIN_GUIDE);
        }
        Set<String> members = redis.opsForZSet()
                .reverseRange(RedisKeyConstants.feedInbox(userId), 0, INBOX_SCAN_LIMIT - 1);
        if (members == null || members.isEmpty()) {
            return emptyFeed("关注", "关注的教练和朋友发的公开动态会出现在这里。");
        }
        List<Long> inboxIds = members.stream().map(Long::valueOf).toList();
        Long lastId = decodeIdCursor(cursor);

        // 收件箱里只有 PUBLIC（扇出时判过），所以这里不再判可见性；status 要判——作者删动态后
        // 收件箱里的 id 还在（一期不回收），回表时靠 status=1 过滤
        List<Post> rows = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .in(Post::getId, inboxIds)
                .lt(lastId != null, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (pageSize + 1)));
        return timeFeedResult(rows, pageSize, "关注", "关注的动态都看完了。", userId);
    }

    /**
     * 好友动态：PUBLIC + FRIEND 两档（docs 4.12 K3 的"拉模式"那一行）。
     * <p>★"好友"= 互关，靠 {@code t_follow} 自连接现算，<b>不建 t_friend 表</b>：
     * 建了就要在关注/取关时双向同步，同步失败会出现"我显示他是好友、他不显示"。</p>
     */
    private FeedResultVO friendFeed(int pageSize, String cursor, Long userId) {
        if (userId == null) {
            return emptyFeed("好友", LOGIN_GUIDE);
        }
        List<Long> friends = followService.mutualFriendIds(userId);
        if (friends.isEmpty()) {
            return emptyFeed("好友", "还没有互关的好友。和朋友互相关注后，这里能看到他们「仅好友可见」的动态。");
        }
        Long lastId = decodeIdCursor(cursor);
        List<Post> rows = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .in(Post::getUserId, friends)
                .in(Post::getVisibility, List.of(VIS_PUBLIC, VIS_FRIEND))
                .lt(lastId != null, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (pageSize + 1)));
        return timeFeedResult(rows, pageSize, "好友", "好友们还没发动态。", userId);
    }

    /** 我发布的：三档都要有，所以不判 visibility（作者自己当然看得到自己的私密） */
    private FeedResultVO mineFeed(int pageSize, String cursor, Long userId) {
        if (userId == null) {
            return emptyFeed("我的", LOGIN_GUIDE);
        }
        return authorFeed(userId, pageSize, cursor, userId, true, "我的动态", "还没发过动态。");
    }

    @Override
    public FeedResultVO authorPosts(Long authorId, String cursor, Integer size, Long userId) {
        if (authorId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少作者 id");
        }
        return authorFeed(authorId, clampSize(size), cursor, userId, authorId.equals(userId),
                "TA的动态", "这位用户还没发过动态。");
    }

    /**
     * 作者主页：自己的含 PRIVATE，别人的只有 PUBLIC，互关好友多看一档 FRIEND。
     *
     * @param includeAll 是否看得到该作者的全部可见性（本人=true）
     */
    private FeedResultVO authorFeed(Long authorId, int pageSize, String cursor, Long userId,
                                    boolean includeAll, String tabText, String emptyText) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 1)
                .eq(Post::getUserId, authorId);
        if (!includeAll) {
            boolean friend = followService.isMutualFollow(userId, authorId);
            wrapper.in(Post::getVisibility, friend
                    ? List.of(VIS_PUBLIC, VIS_FRIEND) : List.of(VIS_PUBLIC));
        }
        Long lastId = decodeIdCursor(cursor);
        List<Post> rows = postMapper.selectList(wrapper
                .lt(lastId != null, Post::getId, lastId)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (pageSize + 1)));
        return timeFeedResult(rows, pageSize, tabText, emptyText, userId);
    }

    @Override
    public FeedResultVO myLikes(String cursor, Integer size, Long userId) {
        requireLogin(userId);
        int pageSize = clampSize(size);
        Long lastId = decodeIdCursor(cursor);
        // ★游标是"点赞记录 id"而不是动态 id：这个 tab 的语义是"我最近赞过什么"，
        // 按点赞时间倒序才对；用动态 id 排序会变成"赞过的动态里最老/最新的"，两回事
        List<PostLike> rows = postLikeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getUserId, userId)
                .lt(lastId != null, PostLike::getId, lastId)
                .orderByDesc(PostLike::getId)
                .last("LIMIT " + (pageSize + 1)));
        boolean hasMore = rows.size() > pageSize;
        List<PostLike> page = hasMore ? rows.subList(0, pageSize) : rows;
        if (page.isEmpty()) {
            return emptyFeed("点赞", "还没有赞过动态。");
        }
        List<Long> postIds = page.stream().map(PostLike::getPostId).toList();
        // 作者删掉的动态直接过滤；★还要再判一次可见性：
        // 点赞时是 FRIEND/互关，取关之后这条动态就不该继续出现在“我的点赞”里。
        List<Post> posts = orderByIds(loadByIds(postIds).stream()
                .filter(p -> p.getStatus() == 1)
                .filter(p -> canView(p, userId))
                .toList(), postIds);
        return FeedResultVO.builder()
                .list(assemble(posts, userId))
                .nextCursor(hasMore ? String.valueOf(page.get(page.size() - 1).getId()) : null)
                .hasMore(hasMore)
                .tabText("点赞")
                .emptyText(null)
                .build();
    }

    @Override
    public FeedResultVO myCollects(String cursor, Integer size, Long userId) {
        requireLogin(userId);
        int pageSize = clampSize(size);
        Long lastId = decodeIdCursor(cursor);
        List<PostCollect> rows = postCollectMapper.selectList(new LambdaQueryWrapper<PostCollect>()
                .eq(PostCollect::getUserId, userId)
                .lt(lastId != null, PostCollect::getId, lastId)
                .orderByDesc(PostCollect::getId)
                .last("LIMIT " + (pageSize + 1)));
        boolean hasMore = rows.size() > pageSize;
        List<PostCollect> page = hasMore ? rows.subList(0, pageSize) : rows;
        if (page.isEmpty()) {
            return emptyFeed("收藏", "还没有收藏过动态。");
        }
        List<Long> postIds = page.stream().map(PostCollect::getPostId).toList();
        // 同“我的点赞”：删掉的、以及不再对自己可见的动态都要过滤
        List<Post> posts = orderByIds(loadByIds(postIds).stream()
                .filter(p -> p.getStatus() == 1)
                .filter(p -> canView(p, userId))
                .toList(), postIds);
        return FeedResultVO.builder()
                .list(assemble(posts, userId))
                .nextCursor(hasMore ? String.valueOf(page.get(page.size() - 1).getId()) : null)
                .hasMore(hasMore)
                .tabText("收藏")
                .emptyText(null)
                .build();
    }

    // =====================================================================
    // 详情 / 发布 / 删除
    // =====================================================================
    @Override
    public PostVO detail(Long postId, Long userId) {
        Post post = loadExisting(postId);
        assertVisible(post, userId);
        // ★浏览计数放在可见性之后：不给看的人不该贡献浏览量
        // （否则"私密动态被拒"也会把分数抬上去，正好是被刷的那个方向）
        postViewService.countView(postId, userId);
        return assemble(List.of(post), userId).get(0);
    }

    @Override
    public PostVO publish(Long userId, PublishDTO dto) {
        requireLogin(userId);
        String visibility = normalizeVisibility(dto.getVisibility());
        UserBriefDTO user = fetchUser(userId);

        Post post = new Post();
        post.setUserId(userId);
        post.setUserName(user != null ? user.getNickname() : "悦动同学");
        post.setAvatarColor(user != null && user.getAvatarColor() != null ? user.getAvatarColor() : "0");
        post.setContent(dto.getContent().trim());
        post.setImages(joinImages(dto.getImages()));
        post.setTopic(dto.getTopic() == null || dto.getTopic().isBlank() ? "打卡日常" : dto.getTopic().trim());
        post.setVisibility(visibility);
        post.setLikeCount(0);
        post.setCollectCount(0);
        post.setCommentCount(0);
        post.setViewCount(0);
        post.setStatus(1);
        // ★显式赋值而不是靠 DB 的 DEFAULT CURRENT_TIMESTAMP：
        // insert 之后实体里拿不到数据库填的那个值，而下面的扇出要用发布时间当 ZSet 的 score、
        // 返回的 VO 也要算"刚刚/X 分钟前"。旧实现留 null，toVO 里 relativeTime 直接 NPE
        post.setCreatedAt(LocalDateTime.now());
        postMapper.insert(post);

        // ===== 只有 PUBLIC 动态扇出到粉丝收件箱（docs 4.12 K3）=====
        // FRIEND/PRIVATE 一旦进了别人的收件箱就等于公开了，这是不可逆的泄露，
        // 所以这一句 if 是整个可见性设计里最不能省的一处
        if (VIS_PUBLIC.equals(visibility)) {
            fanOutToFollowers(userId, post.getId(), post.getCreatedAt());
        }
        return assemble(List.of(post), userId).get(0);
    }

    @Override
    public void deletePost(Long userId, Long postId) {
        requireLogin(userId);
        Post post = loadExisting(postId);
        if (!post.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己发布的动态");
        }
        Post update = new Post();
        update.setId(postId);
        update.setStatus(0);
        postMapper.updateById(update);
        // ★不清粉丝收件箱里的 id：那是缓存，回表时靠 status=1 过滤就看不到了。
        // 同理，取关之后对方已扇出的 PUBLIC 动态也不撤回（一期明确不做，别顺手"优化"成去查收件箱删成员）
    }

    // =====================================================================
    // 点赞 / 收藏：结构完全对称，两张表（docs 4.12 的"不合并"裁定）
    // =====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LikeResultVO like(Long userId, Long postId) {
        requireLogin(userId);
        Post post = loadExisting(postId);
        assertVisible(post, userId);

        PostLike existed = postLikeMapper.selectOne(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId)
                .eq(PostLike::getUserId, userId));

        boolean liked;
        if (existed != null) {
            // 已点赞 → 取消：删关系行 + 计数 -1，两步同库同事务。
            // ★只有真的删掉 1 行才减计数：并发双击取消时，第二次 delete 返回 0，
            //   如果还无条件 -1，就会把计数减两次。
            int deleted = postLikeMapper.deleteById(existed.getId());
            if (deleted > 0) {
                postMapper.changeLikeCount(postId, -1);
            }
            liked = false;
        } else {
            PostLike like = new PostLike();
            like.setPostId(postId);
            like.setUserId(userId);
            like.setCreatedAt(LocalDateTime.now());
            try {
                postLikeMapper.insert(like);
                postMapper.changeLikeCount(postId, +1);
                liked = true;
                if (!post.getUserId().equals(userId)) {
                    notifyPublisher.publishCommunityNotify(post.getUserId(), "收到点赞",
                            post.getUserName() + "，你的动态「" + snippet(post.getContent()) + "」收到了新的点赞。");
                }
            } catch (DuplicateKeyException e) {
                // ★并发双击：uk_post_user 拦下第二次插入，按"已点赞"返回。
                // 这是"必须防"的那一档——不是防线程，是防用户双击出来的两个请求
                liked = true;
            }
        }
        Post latest = postMapper.selectById(postId);
        return new LikeResultVO(liked, latest.getLikeCount());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CollectResultVO collect(Long userId, Long postId) {
        requireLogin(userId);
        Post post = loadExisting(postId);
        assertVisible(post, userId);

        PostCollect existed = postCollectMapper.selectOne(new LambdaQueryWrapper<PostCollect>()
                .eq(PostCollect::getPostId, postId)
                .eq(PostCollect::getUserId, userId));

        boolean collected;
        if (existed != null) {
            // 同点赞：只有真的删掉关系行才减计数，防止并发双击减两次
            int deleted = postCollectMapper.deleteById(existed.getId());
            if (deleted > 0) {
                postMapper.changeCollectCount(postId, -1);
            }
            collected = false;
        } else {
            PostCollect collect = new PostCollect();
            collect.setPostId(postId);
            collect.setUserId(userId);
            collect.setCreatedAt(LocalDateTime.now());
            try {
                postCollectMapper.insert(collect);
                postMapper.changeCollectCount(postId, +1);
                collected = true;
                if (!post.getUserId().equals(userId)) {
                    // ★收藏也发通知，且文案和点赞分开：作者在乎的区别是"有人要留着看"，
                    // 两条一样的内容会被当成同一条噪音
                    notifyPublisher.publishCommunityNotify(post.getUserId(), "动态被收藏",
                            post.getUserName() + "，你的动态「" + snippet(post.getContent()) + "」被人收藏了。");
                }
            } catch (DuplicateKeyException e) {
                collected = true;
            }
        }
        Post latest = postMapper.selectById(postId);
        return new CollectResultVO(collected, latest.getCollectCount());
    }

    // =====================================================================
    // 评论：两级封顶（一级分页 + 每条带前几条回复），不匿名
    // =====================================================================
    @Override
    public CommentPageVO comments(Long postId, String cursor, Integer size, Long userId) {
        Post post = loadExisting(postId);
        assertVisible(post, userId);

        int pageSize = clampSize(size);
        Long lastId = decodeIdCursor(cursor);
        // ★不过滤 status：删掉的一级要留占位，它下面的回复才有地方挂（见 CommentVO.deleted）
        List<Comment> roots = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, postId)
                .eq(Comment::getRootId, 0L)
                .gt(lastId != null, Comment::getId, lastId)
                .orderByAsc(Comment::getId)
                .last("LIMIT " + (pageSize + 1)));
        boolean hasMore = roots.size() > pageSize;
        List<Comment> page = hasMore ? roots.subList(0, pageSize) : roots;
        if (page.isEmpty()) {
            return CommentPageVO.builder().list(List.of()).nextCursor(null).hasMore(false).build();
        }

        // ===== 整页回复数：一条 GROUP BY 拿完，★不在循环里 COUNT =====
        List<Long> rootIds = page.stream().map(Comment::getId).toList();
        Map<Long, Integer> countMap = new HashMap<>();
        if (!rootIds.isEmpty()) {
            for (ReplyCountRow row : commentMapper.selectReplyCounts(rootIds)) {
                countMap.put(row.getRootId(), row.getReplyCount());
            }
        }

        List<CommentVO> list = new ArrayList<>();
        for (Comment root : page) {
            int total = countMap.getOrDefault(root.getId(), 0);
            // ★前几条回复逐条查：一页 10 条一级就是 10 次走 idx_root_time 的小查询。
            // 合并成一条的写法是 ROW_NUMBER() OVER (PARTITION BY root_id ORDER BY id)，
            // MySQL 8 支持但读起来要绕一层子查询，这个量级不值得——真到爆款评论量再换
            List<Comment> preview = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getRootId, root.getId())
                    .orderByAsc(Comment::getId)
                    .last("LIMIT " + REPLY_PREVIEW_SIZE));
            list.add(toCommentVO(root, userId, total, total > REPLY_PREVIEW_SIZE,
                    preview.stream().map(c -> toCommentVO(c, userId, 0, false, List.of())).toList()));
        }
        return CommentPageVO.builder()
                .list(list)
                .nextCursor(hasMore ? String.valueOf(page.get(page.size() - 1).getId()) : null)
                .hasMore(hasMore)
                .build();
    }

    @Override
    public CommentPageVO replies(Long rootId, String cursor, Integer size, Long userId) {
        Comment root = commentMapper.selectById(rootId);
        if (root == null) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 回复页的可见性跟着所属动态判：能看动态才能看它的回复
        assertVisible(loadExisting(root.getPostId()), userId);

        int pageSize = clampSize(size);
        Long lastId = decodeIdCursor(cursor);
        List<Comment> rows = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getRootId, rootId)
                .gt(lastId != null, Comment::getId, lastId)
                .orderByAsc(Comment::getId)
                .last("LIMIT " + (pageSize + 1)));
        boolean hasMore = rows.size() > pageSize;
        List<Comment> page = hasMore ? rows.subList(0, pageSize) : rows;
        // 回复自己不再挂回复（两级封顶），所以 replies 恒为空、replyCount 恒为 0
        return CommentPageVO.builder()
                .list(page.stream().map(c -> toCommentVO(c, userId, 0, false, List.of())).toList())
                .nextCursor(hasMore && !page.isEmpty()
                        ? String.valueOf(page.get(page.size() - 1).getId()) : null)
                .hasMore(hasMore)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommentVO addComment(Long userId, CommentDTO dto) {
        requireLogin(userId);
        // ===== 第 1 步：动态存在且对他可见 =====
        Post post = loadExisting(dto.getPostId());
        assertVisible(post, userId);

        // ===== 第 2 步：回复时校验父评论必须属于同一条动态 =====
        // ★这个跨表判断不能省：只查 parentId 存在不查归属，
        // 别人就能把 parentId 传成另一条动态的评论，拼出一条挂错地方的回复
        String replyToUser = null;
        long rootId = 0L;
        Comment parent = null;
        if (dto.getParentId() != null) {
            parent = commentMapper.selectById(dto.getParentId());
            if (parent == null || !parent.getPostId().equals(dto.getPostId())) {
                throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
            }
            replyToUser = parent.getUserName();
            // ★rootId 的两种情况：回复一级 → parent 自己就是一级（它的 root_id=0），root 就是它；
            //   回复二级 → 继承 parent 的 root_id。这样"两级"是靠 root_id 拍的平铺结构，
            //   不是靠递归查 parent 链
            rootId = parent.getRootId() == null || parent.getRootId() == 0L
                    ? parent.getId() : parent.getRootId();
        }

        // ===== 第 3 步：昵称/头像快照（Feign）并落库 =====
        UserBriefDTO user = fetchUser(userId);
        Comment comment = new Comment();
        comment.setPostId(dto.getPostId());
        comment.setRootId(rootId);
        comment.setParentId(dto.getParentId());
        comment.setUserId(userId);
        comment.setUserName(user != null ? user.getNickname() : "悦动同学");
        comment.setAvatarColor(user != null && user.getAvatarColor() != null ? user.getAvatarColor() : "0");
        comment.setContent(dto.getContent().trim());
        comment.setReplyToUser(replyToUser);
        comment.setStatus(1);
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);

        // ===== 第 4 步：评论数 +1（含回复），再发通知 =====
        postMapper.changeCommentCount(dto.getPostId(), +1);
        if (parent != null && !parent.getUserId().equals(userId)) {
            // ★回复场景通知"被回复的人"而不是动态作者：作者可能被一个爆款评论刷屏，
            // 而"有人回我那句"才是他真正要看到的
            notifyPublisher.publishCommunityNotify(parent.getUserId(), "收到新回复",
                    parent.getUserName() + "，「" + snippet(comment.getContent()) + "」回复了你的评论。");
        } else if (parent == null && !post.getUserId().equals(userId)) {
            notifyPublisher.publishCommunityNotify(post.getUserId(), "收到新评论",
                    post.getUserName() + "，你的动态「" + snippet(post.getContent()) + "」有新评论："
                            + snippet(comment.getContent()));
        }
        return toCommentVO(comment, userId, 0, false, List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long userId, Long commentId) {
        requireLogin(userId);
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己的评论");
        }

        // 改：status 1→0，带 status=1 条件。影响 0 行 = 已被并发请求删过，
        // 这时不能再减评论数，否则双击删除会把计数减两次。
        int deleted = commentMapper.softDelete(commentId, userId);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // ★评论数 -1（GREATEST 兜住负数）；占位仍在列表里，回复链不断
        postMapper.changeCommentCount(comment.getPostId(), -1);
    }

    // =====================================================================
    // 管理端审核
    // =====================================================================
    @Override
    public PageDTO<CommunityAdminDTO> adminPage(String keyword, Integer status, Integer page, Integer size) {
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 50);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();

        Page<Post> result = postMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<Post>()
                        // 关键词只搜内容/昵称/话题：审核最常用的三个入口
                        .and(kw != null, w -> w.like(Post::getContent, kw)
                                .or().like(Post::getUserName, kw)
                                .or().like(Post::getTopic, kw))
                        .eq(status != null, Post::getStatus, status)
                        .orderByDesc(Post::getId));

        List<CommunityAdminDTO> rows = new ArrayList<>(result.getRecords().size());
        for (Post post : result.getRecords()) {
            rows.add(toAdminDTO(post));
        }
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public void adminSetStatus(Long postId, Integer status) {
        if (!Integer.valueOf(POST_STATUS_NORMAL).equals(status)
                && !Integer.valueOf(POST_STATUS_HIDDEN).equals(status)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "审核状态只能是 1（恢复）或 2（隐藏）");
        }
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        // ★作者已经删掉的动态不能被审核“恢复”：那会绕过作者的删除意愿
        if (Integer.valueOf(POST_STATUS_DELETED).equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "作者已删除的动态不能恢复");
        }
        Post patch = new Post();
        patch.setId(postId);
        patch.setStatus(status);
        postMapper.updateById(patch);
        log.info("管理端修改动态状态: postId={}, status={}", postId, status);
    }

    @Override
    public void adminDelete(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        if (Integer.valueOf(POST_STATUS_DELETED).equals(post.getStatus())) {
            return;
        }
        Post patch = new Post();
        patch.setId(postId);
        patch.setStatus(POST_STATUS_DELETED);
        postMapper.updateById(patch);
        log.info("管理端删除动态: postId={}", postId);
    }

    private CommunityAdminDTO toAdminDTO(Post post) {
        return CommunityAdminDTO.builder()
                .id(post.getId())
                .userId(post.getUserId())
                .userName(post.getUserName())
                .avatarIdx(parseAvatar(post.getAvatarColor()))
                .content(post.getContent())
                .images(splitImages(post.getImages()))
                .topic(post.getTopic())
                .visibility(post.getVisibility())
                .visibilityText(visibilityText(post.getVisibility()))
                .likeCount(post.getLikeCount())
                .collectCount(post.getCollectCount())
                .commentCount(post.getCommentCount())
                .viewCount(post.getViewCount())
                .status(post.getStatus())
                .statusText(postStatusText(post.getStatus()))
                .createdAt(post.getCreatedAt())
                .build();
    }

    private String postStatusText(Integer status) {
        if (Integer.valueOf(POST_STATUS_HIDDEN).equals(status)) {
            return "审核隐藏";
        }
        if (Integer.valueOf(POST_STATUS_DELETED).equals(status)) {
            return "已删除";
        }
        return "正常";
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 一页夹到 1~20，前端传什么都不至于把接口拖成全表扫 */
    private int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 写接口必须有人（点赞/收藏/发布/评论/删除）。
     * <p>★正常情况下网关已经把这批口拦在 401 了（它们不在白名单里），这里仍然判一次，
     * 因为<b>信任边界的成本极低、收益是"错误形状不吓人"</b>：
     * 少了这一句，userId=null 会走到 MyBatis-Plus 的 {@code eq(user_id, null)}，
     * 生成一个永远匹配不上的 SQL 条件，然后插入时撞 NOT NULL 抛 500——
     * 一个"没登录"的小事变成一条系统异常，前端只能显示"系统繁忙"。
     * 这不是防并发，是防"绕过网关直连 8105"和"以后有人改了白名单"。</p>
     */
    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** 时间流游标：就是一个动态/评论/互动记录的 id，空串与 null 都算首页 */
    private Long decodeIdCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(cursor.trim());
        } catch (NumberFormatException e) {
            // ★坏游标当首页而不是抛错：游标是前端回传的不透明串，
            // 版本换了或手工改过的脏值不该让接口 500
            log.warn("游标解析失败，按首页处理: {}", cursor);
            return null;
        }
    }

    /**
     * 推荐流游标：{@code 分数_id}。
     * <p>★分数和 id 都要参与下一页比较：只比较分数时，上一页末尾同分的动态
     * 会在第二页被整批跳过。首页用"分数极大 + id 极大"作为哨兵。</p>
     */
    private ScoreCursor decodeScoreCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return ScoreCursor.firstPage();
        }
        int idx = cursor.lastIndexOf('_');
        if (idx <= 0) {
            log.warn("推荐流游标格式异常，按首页处理: {}", cursor);
            return ScoreCursor.firstPage();
        }
        try {
            double score = Double.parseDouble(cursor.substring(0, idx));
            long postId = Long.parseLong(cursor.substring(idx + 1));
            return new ScoreCursor(score, postId);
        } catch (NumberFormatException e) {
            log.warn("推荐流游标格式异常，按首页处理: {}", cursor);
            return ScoreCursor.firstPage();
        }
    }

    /** 推荐流游标的两段值：分数 + 同分时的 post_id 锚点 */
    private static class ScoreCursor {

        private final double score;
        private final long postId;

        private ScoreCursor(double score, long postId) {
            this.score = score;
            this.postId = postId;
        }

        private static ScoreCursor firstPage() {
            return new ScoreCursor(PostMapper.FIRST_PAGE_SCORE, Long.MAX_VALUE);
        }

        private double getScore() {
            return score;
        }

        private long getPostId() {
            return postId;
        }
    }

    /** 动态必须存在且未删除 */
    private Post loadExisting(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() != 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /**
     * 可见性判定（docs 4.12 K3）。★三档各判各的，不写成一个大 OR：
     * 列表侧已经按流把 visibility 筛过了，这里只服务"单条"场景（详情、评论、点赞、收藏）。
     */
    private void assertVisible(Post post, Long userId) {
        if (!canView(post, userId)) {
            throw new BusinessException(ErrorCode.POST_NOT_VISIBLE);
        }
    }

    /**
     * 单条动态的可见性判断，返回布尔而不是抛异常。
     * <p>★给“我的点赞/收藏”这类<b>列表过滤</b>用：列表里有一条不可见，
     * 应该把它过滤掉，而不是让整页 500。单条详情仍然用 {@link #assertVisible}。</p>
     */
    private boolean canView(Post post, Long userId) {
        if (VIS_PUBLIC.equals(post.getVisibility())) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        if (userId.equals(post.getUserId())) {
            return true; // 作者永远看得到自己的
        }
        if (VIS_PRIVATE.equals(post.getVisibility())) {
            return false;
        }
        // FRIEND：只有和作者互关的人能看
        return followService.isMutualFollow(userId, post.getUserId());
    }

    /** 入参可见性：留空按 PUBLIC，出现别的值直接拒绝（★不能"默认当公开"，那是隐私事故） */
    private String normalizeVisibility(String raw) {
        if (raw == null || raw.isBlank()) {
            return VIS_PUBLIC;
        }
        String value = raw.trim().toUpperCase();
        if (!VIS_PUBLIC.equals(value) && !VIS_FRIEND.equals(value) && !VIS_PRIVATE.equals(value)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "可见性只能是 PUBLIC / FRIEND / PRIVATE");
        }
        return value;
    }

    /**
     * 推模式扇出：把动态 id 投进每个粉丝的收件箱。
     *
     * <p>★一期<b>同步</b>做：量级是"几十个粉丝"，异步要配线程池、要管拒绝策略、
     * 还要处理"扇出失败要不要重投"，收益不抵复杂度。
     * 真正的转折点是大 V：几十万粉丝同步扇出会把发布接口拖死，那时改"推拉结合"
     * （活跃粉丝推、僵尸粉读时去关注列表拉），不是把这段代码挪进线程池。</p>
     */
    private void fanOutToFollowers(Long authorId, Long postId, LocalDateTime createdAt) {
        List<Long> followerIds = followService.followerIds(authorId);
        if (followerIds.isEmpty()) {
            return;
        }
        double score = createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (Long fanId : followerIds) {
            String inbox = RedisKeyConstants.feedInbox(fanId);
            redis.opsForZSet().add(inbox, String.valueOf(postId), score);
            // 收件箱只留最近 1000 条：ZSet 按 score 升序，删掉头部（最老的）多余部分
            redis.opsForZSet().removeRange(inbox, 0, -INBOX_SCAN_LIMIT - 1);
        }
    }

    /** 按 id 回表（一次 IN 查询），★并保住调用方给的那个顺序 */
    private List<Post> loadByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return postMapper.selectList(new LambdaQueryWrapper<Post>().in(Post::getId, ids));
    }

    /** {@code selectList} 的返回顺序按主键，不保证是 ids 的顺序；流的分次序必须原样保留 */
    private List<Post> orderByIds(List<Post> posts, List<Long> ids) {
        Map<Long, Post> map = posts.stream()
                .collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));
        List<Post> ordered = new ArrayList<>();
        for (Long id : ids) {
            Post p = map.get(id);
            if (p != null) {
                ordered.add(p);
            }
        }
        return ordered;
    }

    /** 组装一页：三个"我相关"标记各一次批量查，浏览量叠上未入账增量 */
    private List<PostVO> assemble(List<Post> posts, Long userId) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = posts.stream().map(Post::getId).toList();
        Set<Long> likedIds = likedPostIds(userId, ids);
        Set<Long> collectedIds = collectedPostIds(userId, ids);
        Map<Long, Integer> deltas = postViewService.currentDeltas(ids);
        // 作者头像：一页动态只发一次批量 Feign，不在循环里逐条查用户
        Map<Long, String> avatarImages = loadAvatarImages(posts);
        return posts.stream()
                .map(p -> toVO(p, userId, likedIds, collectedIds,
                        deltas.getOrDefault(p.getId(), 0), avatarImages.get(p.getUserId())))
                .toList();
    }

    /**
     * 批量取作者头像路径。
     *
     * <p>昵称/头像色是发布时的快照，但头像图片允许用户随时更换，
     * 所以读 Feed 时补一次当前头像。失败只降级成渐变色，不影响社区可用。</p>
     */
    private Map<Long, String> loadAvatarImages(List<Post> posts) {
        List<Long> userIds = posts.stream().map(Post::getUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            Result<List<UserBriefDTO>> result = userClient.batch(new BatchUserQueryDTO(userIds));
            if (result.getCode() != 0 || result.getData() == null) {
                return Map.of();
            }
            return result.getData().stream()
                    .filter(u -> u.getAvatarImage() != null && !u.getAvatarImage().isBlank())
                    .collect(Collectors.toMap(UserBriefDTO::getId, UserBriefDTO::getAvatarImage,
                            (first, second) -> first));
        } catch (Exception e) {
            log.warn("批量获取作者头像失败，回退渐变色: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 当前用户对这批动态的点赞 id 集合（一次查出，内存标记，★不在循环里逐条 EXISTS） */
    private Set<Long> likedPostIds(Long userId, List<Long> postIds) {
        if (userId == null || postIds.isEmpty()) {
            return Set.of();
        }
        return postLikeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                        .eq(PostLike::getUserId, userId)
                        .in(PostLike::getPostId, postIds))
                .stream().map(PostLike::getPostId).collect(Collectors.toSet());
    }

    private Set<Long> collectedPostIds(Long userId, List<Long> postIds) {
        if (userId == null || postIds.isEmpty()) {
            return Set.of();
        }
        return postCollectMapper.selectList(new LambdaQueryWrapper<PostCollect>()
                        .eq(PostCollect::getUserId, userId)
                        .in(PostCollect::getPostId, postIds))
                .stream().map(PostCollect::getPostId).collect(Collectors.toSet());
    }

    /** 时间流的一页统一从这里出：多取的那一条只用来判断 hasMore，不返回给前端 */
    private FeedResultVO timeFeedResult(List<Post> rows, int pageSize, String tabText,
                                        String emptyText, Long userId) {
        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        return FeedResultVO.builder()
                .list(assemble(page, userId))
                .nextCursor(hasMore && !page.isEmpty()
                        ? String.valueOf(page.get(page.size() - 1).getId()) : null)
                .hasMore(hasMore)
                .tabText(tabText)
                .emptyText(page.isEmpty() ? emptyText : null)
                .build();
    }

    private FeedResultVO emptyFeed(String tabText, String emptyText) {
        return FeedResultVO.builder()
                .list(List.of()).nextCursor(null).hasMore(false)
                .tabText(tabText).emptyText(emptyText)
                .build();
    }

    /** Feign 取用户昵称/头像（失败降级为默认值，不让社区挂掉） */
    private UserBriefDTO fetchUser(Long userId) {
        try {
            Result<List<UserBriefDTO>> result = userClient.batch(new BatchUserQueryDTO(List.of(userId)));
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

    private PostVO toVO(Post p, Long userId, Set<Long> likedIds, Set<Long> collectedIds,
                        int viewDelta, String avatarImage) {
        return PostVO.builder()
                .id(p.getId())
                .userId(p.getUserId())
                .nickname(p.getUserName())
                .avatarIdx(parseAvatar(p.getAvatarColor()))
                .avatarImage(avatarImage)
                .content(p.getContent())
                .images(splitImages(p.getImages()))
                .likes(p.getLikeCount())
                .liked(likedIds.contains(p.getId()))
                .collects(p.getCollectCount())
                .collected(collectedIds.contains(p.getId()))
                .comments(p.getCommentCount())
                // ★账本 + 未入账增量：定时任务 30 秒才刷一次库，用户"刚看完就 +1"的体感才对得上
                .views(p.getViewCount() + viewDelta)
                .topic(p.getTopic())
                .visibility(p.getVisibility())
                .visibilityText(visibilityText(p.getVisibility()))
                .time(relativeTime(p.getCreatedAt()))
                .mine(userId != null && p.getUserId().equals(userId))
                .build();
    }

    private CommentVO toCommentVO(Comment c, Long userId, int replyCount,
                                  boolean hasMoreReplies, List<CommentVO> replies) {
        boolean deleted = c.getStatus() == null || c.getStatus() != 1;
        return CommentVO.builder()
                .id(c.getId())
                .userId(c.getUserId())
                .userName(c.getUserName())
                .avatarIdx(parseAvatar(c.getAvatarColor()))
                .content(deleted ? DELETED_TEXT : c.getContent())
                .replyToUser(c.getReplyToUser())
                .rootId(c.getRootId())
                .time(relativeTime(c.getCreatedAt()))
                .replyCount(replyCount)
                .hasMoreReplies(hasMoreReplies)
                .replies(replies)
                .mine(!deleted && userId != null && c.getUserId().equals(userId))
                .deleted(deleted)
                .build();
    }

    /** 可见性文案（★字典只在后端：加一档可见性只改这里，不用动四个前端页面） */
    private String visibilityText(String visibility) {
        if (VIS_FRIEND.equals(visibility)) {
            return "仅好友可见";
        }
        if (VIS_PRIVATE.equals(visibility)) {
            return "仅自己可见";
        }
        return "公开";
    }

    /** 头像配色快照是字符串，脏值（历史数据/手填）按默认 0 号，不让一次解析失败毁掉整页 Feed */
    private int parseAvatar(String color) {
        if (color == null || color.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(color.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 相对时间文案（与原 Mock 表现一致） */
    private String relativeTime(LocalDateTime time) {
        if (time == null) {
            return "刚刚";
        }
        Duration d = Duration.between(time, LocalDateTime.now());
        if (d.toMinutes() < 1) return "刚刚";
        if (d.toHours() < 1) return d.toMinutes() + " 分钟前";
        if (d.toHours() < 24) return d.toHours() + " 小时前";
        if (d.toDays() == 1) return "昨天 " + time.format(DateTimeFormatter.ofPattern("HH:mm"));
        return time.format(TIME_FMT);
    }

    /** 配图列表 → csv（DB 就一列）。空列表存空串，读的时候统一回空数组 */
    private String joinImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        String csv = images.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(","));
        // t_post.images 是 VARCHAR(1000)；超长直接拒绝，别让 MySQL 截断出一个坏 URL
        if (csv.length() > 1000) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "配图总长度超出限制，请减少图片");
        }
        return csv;
    }

    /** 配图 csv → 列表。空串/脏段都跳过，不因一条旧数据让整个 Feed 报错 */
    private List<String> splitImages(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private String snippet(String text) {
        return text.length() > 20 ? text.substring(0, 20) + "…" : text;
    }
}
