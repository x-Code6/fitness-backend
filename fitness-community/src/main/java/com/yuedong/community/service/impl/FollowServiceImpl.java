package com.yuedong.community.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.UserBriefDTO;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.community.mapper.FollowMapper;
import com.yuedong.community.model.entity.Follow;
import com.yuedong.community.model.vo.FollowUserVO;
import com.yuedong.community.service.FollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 关注/粉丝实现。
 *
 * <p>存储分工：</p>
 * <ul>
 *   <li>DB t_follow —— 权威数据（重启/Redis 清空后可重建）；</li>
 *   <li>Redis Set community:following:{userId} —— 我关注的人，
 *       用于 SINTER 求"共同关注"（集合交集是 Set 的看家本领）；</li>
 *   <li>Redis ZSet feed:inbox:{userId} —— Feed 推模式收件箱（发动态时写入）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private static final String FOLLOWING_KEY = "community:following:";

    private final FollowMapper followMapper;
    private final StringRedisTemplate redis;
    private final UserClient userClient;

    @Override
    public void follow(Long userId, Long followeeId) {
        // ===== 第 1 步：业务校验 =====
        if (Objects.equals(userId, followeeId)) {
            throw new BusinessException(ErrorCode.FOLLOW_SELF);
        }
        Long exists = followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFolloweeId, followeeId));
        if (exists > 0) {
            return; // 幂等：重复关注直接成功
        }

        // ===== 第 2 步：DB 落权威数据 =====
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFolloweeId(followeeId);
        follow.setCreatedAt(LocalDateTime.now());
        followMapper.insert(follow);

        // ===== 第 3 步：维护 Redis 关注集合（SINTER 数据源） =====
        redis.opsForSet().add(FOLLOWING_KEY + userId, String.valueOf(followeeId));
    }

    @Override
    public void unfollow(Long userId, Long followeeId) {
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFolloweeId, followeeId));
        redis.opsForSet().remove(FOLLOWING_KEY + userId, String.valueOf(followeeId));
    }

    @Override
    public boolean isFollowing(Long userId, Long followeeId) {
        // 优先查 Redis Set（O(1)），缺失时回源 DB（简化：直接查 DB，量小够用）
        return followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFolloweeId, followeeId)) > 0;
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
        List<Long> ids = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getFolloweeId, userId).orderByDesc(Follow::getId))
                .stream().map(Follow::getUserId).toList();
        return toUserVOs(ids);
    }

    @Override
    public List<FollowUserVO> commonFollow(Long userId, Long targetId) {
        // ===== 共同关注 = 两个"我关注的人"集合求交集（SINTER） =====
        Set<String> common = redis.opsForSet().intersect(
                FOLLOWING_KEY + userId, FOLLOWING_KEY + targetId);
        if (common == null || common.isEmpty()) {
            return List.of();
        }
        return toUserVOs(common.stream().map(Long::valueOf).toList());
    }

    @Override
    public List<Long> followerIds(Long userId) {
        return followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getFolloweeId, userId))
                .stream().map(Follow::getUserId).toList();
    }

    /** 批量补昵称（Feign 一次调用；用户服务挂掉时降级展示默认昵称） */
    private List<FollowUserVO> toUserVOs(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Map<Long, UserBriefDTO> userMap = new HashMap<>();
        try {
            var result = userClient.batch(new BatchUserQueryDTO(ids));
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
