package com.yuedong.point.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.point.mapper.CheckinRecordMapper;
import com.yuedong.point.mapper.RankArchiveMapper;
import com.yuedong.point.model.entity.CheckinRecord;
import com.yuedong.point.model.entity.RankArchive;
import com.yuedong.point.model.vo.RankItemVO;
import com.yuedong.point.service.RankService;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排行榜实现：本月 ZSet 实时榜 + 历史月归档表。
 *
 * <p>member = userId，score = 当月积分；每次积分变动 ZINCRBY，查询 ZREVRANGE（O(logN)）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankServiceImpl implements RankService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 实时榜一次取多少名 */
    private static final long TOP_N = 50;

    /** 归档时保留前几名。★不是全量：几百名开外的用户 practically 不会去看历史榜，
     *  而全量归档会让任务变成"把整个 ZSet 拖出来"+几万行 INSERT */
    private static final long ARCHIVE_TOP_N = 100;

    /** 归档之后给那个月的 ZSet 留多久。★40 天而不是永久：月榜 key 若不清，一年就是 12 个常驻大 key */
    private static final Duration ARCHIVED_KEY_TTL = Duration.ofDays(40);

    private final StringRedisTemplate redis;
    private final UserClient userClient;
    private final RankArchiveMapper rankArchiveMapper;
    private final CheckinRecordMapper checkinRecordMapper;

    @Override
    public List<RankItemVO> monthly(Long userId) {
        String key = RedisKeyConstants.rankMonth(LocalDate.now().format(MONTH_FMT));

        // ===== 第 1 步：取 Top50（带分数，已按分数降序）=====
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(key, 0, TOP_N - 1);
        List<RankItemVO> list = new ArrayList<>();
        if (tuples != null) {
            int rank = 1;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Long uid = Long.valueOf(tuple.getValue());
                list.add(RankItemVO.builder()
                        .rank(rank++)
                        .userId(uid)
                        .nickname("神秘运动者") // 占位，第 2 步批量补昵称
                        .points(tuple.getScore() == null ? 0 : tuple.getScore().intValue())
                        .me(uid.equals(userId))
                        .build());
            }
        }

        // ===== 第 2 步：Feign 批量补昵称（一次远程调用，不循环单查）=====
        fillNicknames(list);

        // ===== 第 3 步：当前用户在 Top50 之外 → 追加到末尾（带真实名次）=====
        if (userId != null && list.stream().noneMatch(RankItemVO::isMe)) {
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

    /**
     * 历史月榜单：读归档表，名次用<b>归档时固化的 rank_no</b>。
     * <p>★不重算名次：结榜那一刻的名次是历史事实。如果今天用新规则（比如并列按打卡天数二次排序）
     * 去重排上个月的榜，用户截图里的名次和页面就不一样了。</p>
     */
    @Override
    public PageDTO<RankItemVO> history(String season, Integer page, Integer size, Long userId) {
        if (season == null || !season.matches("\\d{6}")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "月份格式应为 yyyyMM，如 202609");
        }
        IPage<RankArchive> result = rankArchiveMapper.pageBySeason(
                new Page<>(page == null || page < 1 ? 1 : page,
                        size == null || size < 1 ? 20 : Math.min(size, 50)), season);

        List<RankItemVO> list = new ArrayList<>();
        for (RankArchive row : result.getRecords()) {
            list.add(RankItemVO.builder()
                    .rank(row.getRankNo())
                    .userId(row.getUserId())
                    .nickname("神秘运动者")
                    .points(row.getScore())
                    .me(row.getUserId().equals(userId))
                    .build());
        }
        fillNicknames(list);
        return PageDTO.of(list, result.getTotal());
    }

    /**
     * 归档上一个自然月。
     *
     * <p>★<b>重跑安全</b>：写入用 {@code INSERT ... ON DUPLICATE KEY UPDATE}
     * （主键 {@code season+user_id}），第二次跑就是把同一批数据再覆盖一遍，
     * 所以这个任务<b>不需要分布式锁</b>。本项目对定时任务只有一条判据：
     * <b>写操作自己带条件（唯一键 / 状态 CAS）就不要锁</b>。</p>
     */
    @Override
    public int archiveLastMonth() {
        LocalDate lastMonth = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        String season = lastMonth.format(MONTH_FMT);
        String key = RedisKeyConstants.rankMonth(season);

        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(key, 0, ARCHIVE_TOP_N - 1);
        if (tuples == null || tuples.isEmpty()) {
            // 那个月的位图/榜单已经不在 Redis 里（被清过或从未产生）。★不抛错：
            // 归档任务对"这个月本来就没人打卡"的正常情况不该报警，日志留一行就够
            log.info("月榜归档跳过：{} 无可归档数据", season);
            return 0;
        }

        // ===== 第 1 步：该月的打卡明细一次查出，用来补"打卡天数"和"最长连续" =====
        Map<Long, int[]> checkinStats = loadMonthCheckinStats(lastMonth);

        List<RankArchive> rows = new ArrayList<>();
        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Long uid = Long.valueOf(tuple.getValue());
            int[] stats = checkinStats.getOrDefault(uid, new int[]{0, 0});
            RankArchive row = new RankArchive();
            row.setSeason(season);
            row.setUserId(uid);
            row.setScore(tuple.getScore() == null ? 0 : tuple.getScore().intValue());
            row.setRankNo(rank++);
            row.setTotalCheckin(stats[0]);
            row.setContinuousBest(stats[1]);
            rows.add(row);
        }
        rankArchiveMapper.upsertBatch(rows);

        // ===== 第 2 步：归档完了才给那个月的实时榜 key 设 TTL =====
        // ★顺序不能反：先设 TTL 再归档，万一归档失败，那个月的榜就随 key 一起没了
        redis.expire(key, ARCHIVED_KEY_TTL);
        log.info("月榜归档完成: season={}, 人数={}, key {} 天后过期", season, rows.size(), ARCHIVED_KEY_TTL.toDays());
        return rows.size();
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 一次 Feign 补完整页昵称；失败保留占位名（榜能看比昵称全更重要） */
    private void fillNicknames(List<RankItemVO> list) {
        if (list.isEmpty()) {
            return;
        }
        List<Long> ids = list.stream().map(RankItemVO::getUserId).toList();
        try {
            Result<List<UserBriefDTO>> result = userClient.batch(new BatchUserQueryDTO(ids));
            if (result.getCode() == 0 && result.getData() != null) {
                Map<Long, String> nameMap = new HashMap<>();
                result.getData().forEach(u -> nameMap.put(u.getId(), u.getNickname()));
                list.forEach(item -> item.setNickname(nameMap.getOrDefault(item.getUserId(), item.getNickname())));
            }
        } catch (Exception e) {
            log.warn("排行榜补昵称失败，降级展示占位名: {}", e.getMessage());
        }
    }

    /**
     * 某月每个用户的 {@code [累计打卡天数, 最长连续天数]}。
     *
     * <p>★一次把整月明细查出来在内存里分组算，而不是"逐个上榜用户查一次"：
     * 归档 100 名就是 1 次查询 vs 100 次查询。</p>
     *
     * <p>⚠️ 一期这里读全月明细（用户数 × 31 行以内），量级再上一个台阶时要改成
     * {@code SELECT user_id, COUNT(*) ... GROUP BY user_id} + 连续天数单独算。</p>
     */
    private Map<Long, int[]> loadMonthCheckinStats(LocalDate monthAnyDay) {
        LocalDate start = monthAnyDay.withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<CheckinRecord> records = checkinRecordMapper.selectList(new LambdaQueryWrapper<CheckinRecord>()
                .between(CheckinRecord::getCheckDate, start, end)
                .orderByAsc(CheckinRecord::getUserId)
                .orderByAsc(CheckinRecord::getCheckDate));

        Map<Long, int[]> stats = new HashMap<>();
        Long currentUser = null;
        LocalDate previousDate = null;
        int run = 0;
        for (CheckinRecord record : records) {
            if (!record.getUserId().equals(currentUser)) {
                // 换人：重置这段连续计数（★必须先重置再算，否则上一个人的尾巴会接到下一个人身上）
                currentUser = record.getUserId();
                previousDate = null;
                run = 0;
            }
            int total = stats.computeIfAbsent(currentUser, k -> new int[]{0, 0})[0] + 1;
            run = (previousDate != null && previousDate.plusDays(1).equals(record.getCheckDate()))
                    ? run + 1 : 1;
            int best = Math.max(stats.get(currentUser)[1], run);
            stats.put(currentUser, new int[]{total, best});
            previousDate = record.getCheckDate();
        }
        return stats;
    }
}
