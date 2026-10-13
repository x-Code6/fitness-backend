package com.yuedong.point.service.impl;

import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.UserBriefDTO;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.point.model.vo.RankItemVO;
import com.yuedong.point.service.RankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排行榜实现：Redis ZSet 实时榜。
 *
 * <p>member = userId，score = 当月积分；每次积分变动 ZINCRBY，
 * 查询 ZREVRANGE（O(logN)），万级用户毫无压力。
 * 赛季（自然月）结束的归档结算在 P2 用延迟消息触发。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankServiceImpl implements RankService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final long TOP_N = 50;

    private final StringRedisTemplate redis;
    private final UserClient userClient;

    @Override
    public List<RankItemVO> monthly(Long userId) {
        String key = RedisKeyConstants.rankMonth(LocalDate.now().format(MONTH_FMT));

        // ===== 第 1 步：取 Top50（带分数，已按分数降序） =====
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(key, 0, TOP_N - 1);
        List<RankItemVO> list = new ArrayList<>();
        if (tuples != null) {
            int rank = 1;
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                Long uid = Long.valueOf(t.getValue());
                list.add(RankItemVO.builder()
                        .rank(rank++)
                        .userId(uid)
                        .nickname("神秘运动者") // 占位，第 2 步批量补昵称
                        .points(t.getScore() == null ? 0 : t.getScore().intValue())
                        .me(uid.equals(userId))
                        .build());
            }
        }

        // ===== 第 2 步：Feign 批量补昵称（一次远程调用，不循环单查） =====
        List<Long> ids = list.stream().map(RankItemVO::getUserId).toList();
        if (!ids.isEmpty()) {
            try {
                var result = userClient.batch(new BatchUserQueryDTO(ids));
                if (result.getCode() == 0 && result.getData() != null) {
                    Map<Long, String> nameMap = new HashMap<>();
                    result.getData().forEach(u -> nameMap.put(u.getId(), u.getNickname()));
                    list.forEach(r -> r.setNickname(nameMap.getOrDefault(r.getUserId(), r.getNickname())));
                }
            } catch (Exception e) {
                // 昵称服务挂了不让排行榜挂掉（服务降级：展示占位昵称）
                log.warn("排行榜补昵称失败，降级展示占位名: {}", e.getMessage());
            }
        }

        // ===== 第 3 步：当前用户在 Top50 之外 → 追加到末尾（带真实名次） =====
        if (userId != null && list.stream().noneMatch(r -> r.isMe())) {
            Long myRank0 = redis.opsForZSet().reverseRank(key, String.valueOf(userId));
            Double myScore = redis.opsForZSet().score(key, String.valueOf(userId));
            if (myRank0 != null && myScore != null) {
                list.add(RankItemVO.builder()
                        .rank(myRank0.intValue() + 1)
                        .userId(userId)
                        .nickname("我")
                        .points(myScore.intValue())
                        .me(true)
                        .build());
            }
        }

        if (list.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "榜单暂时为空");
        }
        return list;
    }
}
