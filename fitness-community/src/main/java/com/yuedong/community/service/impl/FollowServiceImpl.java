package com.yuedong.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.community.mapper.FollowMapper;
import com.yuedong.community.model.entity.Follow;
import com.yuedong.community.model.vo.FollowUserVO;
import com.yuedong.community.service.FollowService;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import com.yuedong.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 关注/粉丝实现。
 *
 * <p>存储分工（只两处，且各司其职）：</p>
 * <ul>
 *   <li>DB {@code t_follow} —— 关注关系的<b>唯一真相</b>。列表、是否已关注、互关判定全从这里出；</li>
 *   <li>Redis ZSet {@code feed:inbox:{userId}} —— Feed <b>推模式收件箱</b>，
 *       是"发动态时扇出"的结果缓存，<b>不是</b>关注关系。它由 CommunityServiceImpl 写。</li>
 * </ul>
 *
 * <p>★旧代码在这里还维护第三份 Redis Set {@code community:following:{userId}}（给 SINTER 用）。
 * 那是典型的"同一个事实存两处，只有一处会被重建"：集合只在关注时 SADD、没有全量重建入口，
 * Redis 一清空，共同关注永久为空，而关注列表（读 DB）照旧 —— 出错时最难查。现在删掉了。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final FollowMapper followMapper;
    private final UserClient userClient;

    @Override
    public void follow(Long userId, Long followeeId) {
        // ===== 第 1 步：业务校验 =====
        // ★判空是因为这里要 insert：userId 为 null 时前面所有查询都只是"查不到"，
        //   到这一步会变成撞 user_id NOT NULL 的 500。同 CommunityServiceImpl#requireLogin 的理由
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (Objects.equals(userId, followeeId)) {
            throw new BusinessException(ErrorCode.FOLLOW_SELF);
        }
        Long exists = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFolloweeId, followeeId));
        if (exists > 0) {
            return; // 幂等：重复关注直接成功
        }

        // ===== 第 2 步：DB 落库（唯一索引 uk_follow_pair 是并发双击的最终防线） =====
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFolloweeId(followeeId);
        follow.setCreatedAt(LocalDateTime.now());
        followMapper.insert(follow);
    }

    @Override
    public void unfollow(Long userId, Long followeeId) {
        // 物理删：关注关系没有历史价值，留软删只是一堆永远查不到的垃圾行
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFolloweeId, followeeId));
    }

    @Override
    public boolean isFollowing(Long userId, Long followeeId) {
        if (userId == null) {
            return false;
        }
        // 走 uk_follow_pair 的最左前缀，等值两列命中，不需要额外索引
        return followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFolloweeId, followeeId)) > 0;
    }

    @Override
    public boolean isMutualFollow(Long a, Long b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        // ★两个方向各查一次，都是 uk_follow_pair 上的等值探查（命中即停），
        // 比"取全部好友集合再 contains"便宜得多——可见性判定是逐条动态做的，集合法会重复取
        return isFollowing(a, b) && isFollowing(b, a);
    }

    @Override
    public List<FollowUserVO> followingList(Long userId) {
        List<Long> ids = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId).orderByDesc(Follow::getId))
                .stream().map(Follow::getFolloweeId).toList();
        return toUserVOs(ids);
    }

    @Override
    public List<FollowUserVO> fansList(Long userId) {
        // "谁关注了我"必须走 idx_followee(followee_id, user_id)：唯一键的最左前缀只顶得住另一头
        List<Long> ids = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getFolloweeId, userId).orderByDesc(Follow::getId))
                .stream().map(Follow::getUserId).toList();
        return toUserVOs(ids);
    }

    @Override
    public List<FollowUserVO> commonFollow(Long userId, Long targetId) {
        // 共同关注 = 两个"我关注的人"集合的交集，从 DB 各查一次再在内存取交
        List<Long> mine = followingIds(userId);
        if (mine.isEmpty()) {
            return List.of();
        }
        Set<Long> targetFollowing = new HashSet<>(followingIds(targetId));
        List<Long> common = new ArrayList<>();
        for (Long id : mine) {
            // 不含"我"和"对方"本人：共同关注是给别人看的社交证据，这两人在列表里没有意义
            if (targetFollowing.contains(id) && !id.equals(userId) && !id.equals(targetId)) {
                common.add(id);
            }
        }
        return toUserVOs(common);
    }

    @Override
    public List<Long> followerIds(Long userId) {
        // 谁关注了我：走 idx_followee(followee_id, user_id)，等值在前
        return followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getFolloweeId, userId))
                .stream().map(Follow::getUserId).toList();
    }

    @Override
    public List<Long> followingIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        // 我关注了谁：走 uk_follow_pair(user_id, followee_id) 的最左前缀
        return followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId).orderByDesc(Follow::getId))
                .stream().map(Follow::getFolloweeId).toList();
    }

    @Override
    public List<Long> mutualFriendIds(Long userId) {
        // 互关判定交给一条自连接 SQL（两侧都命中覆盖索引），★不在循环里逐个 isFollowing
        if (userId == null) {
            return List.of();
        }
        return followMapper.mutualFolloweeIds(userId);
    }

    /** 批量补昵称（Feign 一次调用；用户服务挂掉时降级展示默认昵称） */
    private List<FollowUserVO> toUserVOs(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, UserBriefDTO> userMap = new HashMap<>();
        try {
            Result<List<UserBriefDTO>> result = userClient.batch(new BatchUserQueryDTO(ids));
            if (result.getCode() == 0 && result.getData() != null) {
                result.getData().forEach(u -> userMap.put(u.getId(), u));
            }
        } catch (Exception e) {
            log.warn("关注列表补昵称失败，降级: {}", e.getMessage());
        }
        return ids.stream()
                .map(id -> {
                    UserBriefDTO u = userMap.get(id);
                    return FollowUserVO.builder()
                            .userId(id)
                            .nickname(u != null ? u.getNickname() : "悦动用户")
                            .avatarIdx(u == null || u.getAvatarColor() == null ? 0
                                    : Integer.parseInt(u.getAvatarColor()))
                            .build();
                })
                .toList();
    }
}
