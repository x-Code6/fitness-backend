package com.yuedong.point.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.point.api.dto.PointBriefDTO;
import com.yuedong.point.mapper.CheckinRecordMapper;
import com.yuedong.point.mapper.MemberPointsMapper;
import com.yuedong.point.mapper.PointsLogMapper;
import com.yuedong.point.model.entity.CheckinRecord;
import com.yuedong.point.model.entity.MemberPoints;
import com.yuedong.point.model.entity.PointsLog;
import com.yuedong.point.model.vo.CheckinCalendarVO;
import com.yuedong.point.model.vo.CheckinResultVO;
import com.yuedong.point.model.vo.CheckinTodayVO;
import com.yuedong.point.model.vo.PointsLogVO;
import com.yuedong.point.mq.NotifyPublisher;
import com.yuedong.point.service.CheckinService;
import com.yuedong.point.service.support.CheckinTxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 打卡与积分实现（docs D16 重写）。
 *
 * <p>★<b>写入顺序是这一版最该被讲清楚的一处</b>，一句话原则：
 * <b>不可重建的先落库，可重建的最后写缓存。</b></p>
 * <ol>
 *   <li>先确保 Redis 位图存在（缺了从 DB 明细重建），并<b>从昨天</b>往低数连续天数；</li>
 *   <li>{@link CheckinTxWriter} —— 打卡明细 + 积分流水 + 余额，一个真事务。
 *       撞 {@code uk_user_date} 就是"今天已打过"，并发双击也只有一次能成功；</li>
 *   <li>事务提交后再写今天这一位；Redis 失败只删 key，等待下次重建；</li>
 *   <li>事务成功后才 ZINCRBY 月榜、发通知 —— 旁路失败不能让用户重试一次成功的打卡。</li>
 * </ol>
 *
 * <p>★旧实现第 1 步就是 Redis，DB 里<b>根本没有打卡明细</b>：Redis 一清空，
 * 用户当月打过哪些天、连续几天全部无从解释（P0-5）。现在反过来，
 * Redis 塌了最坏是"位图要重建一次"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinServiceImpl implements CheckinService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int CHECKIN_POINTS = 10;

    /**
     * 位图 offset 的基准日。
     * <p>★不用 epoch(1970) 当基准：那会让 offset ≈ 20000+，一位一天就是 2.6KB/人；
     * 用项目上线年做基准，一年 365 位 = 46 字节/人。
     * 这个常量<b>永远不能改</b>（改了所有历史位图错位），所以写死在这里而不是配置——
     * 配置能被手滑改掉，而这是一个改一次就要重刷全部 key 的决定。</p>
     */
    private static final LocalDate BITMAP_BASELINE = LocalDate.of(2026, 1, 1);

    /**
     * 连续天数最多往前数多少位。
     * <p>★封顶是为了"一个坏位图不会把请求挂住"：正常用户连续几十天，
     * 真出现整串 1（脏数据）时，没有上限就是无界循环。</p>
     */
    private static final int MAX_CONTINUOUS_SCAN = 400;

    private final StringRedisTemplate redis;
    private final MemberPointsMapper memberPointsMapper;
    private final PointsLogMapper pointsLogMapper;
    private final CheckinRecordMapper checkinRecordMapper;
    /**
     * 打卡的事务段：打卡明细 + 积分流水/余额。
     * <p>★为什么单独一个 Bean：Spring 事务靠代理生效，同类里 {@code this.} 调用不走代理，
     * 注解等于白写。这里把事务边界显式放到另一个 Bean 上，读代码的人一眼能看到
     * "哪几步是一起成败的"。</p>
     */
    private final CheckinTxWriter checkinTxWriter;
    private final NotifyPublisher notifyPublisher;

    @Override
    public CheckinTodayVO today(Long userId) {
        // ===== 打过没：读 DB 真相，不读位图 =====
        // ★既然唯一索引是判重的依据，读也要读同一个地方。
        //   写读两处源（写判 DB、显示判 Redis）迟早给出两个答案，而那时没人能判断哪个对
        LocalDate today = LocalDate.now();
        Long checked = checkinRecordMapper.selectCount(new LambdaQueryWrapper<CheckinRecord>()
                .eq(CheckinRecord::getUserId, userId)
                .eq(CheckinRecord::getCheckDate, today));
        MemberPoints member = memberPointsMapper.selectById(userId);
        return CheckinTodayVO.builder()
                .checked(checked != null && checked > 0)
                // 连续天数用打卡时算好并落库的那一列：读一次账户行就够，不再扫位图
                .continuousDays(member == null ? 0 : member.getContinuousDays())
                .build();
    }

    @Override
    public CheckinResultVO doCheckin(Long userId) {
        LocalDate today = LocalDate.now();
        String bitmapKey = RedisKeyConstants.pointCheck(userId);
        long todayOffset = offsetOf(today);

        // ===== 第 1 步：位图是展示缓存，缺了先用 DB 明细补 =====
        // 这一步只补历史，不写"今天"：今天的打卡还没落库，先写位图会制造一条
        // 数据库里不存在的记录，事务失败时也分不清"到底打没打"。
        ensureBitmap(userId, bitmapKey);

        // ===== 第 2 步：从"昨天"开始数连续天数，再 +1 =====
        // 今天能不能打成功要等事务提交，所以不能把今天先算进去。
        int continuousDays = countContinuousBefore(bitmapKey, todayOffset) + 1;

        // ===== 第 3 步：打卡明细 + 积分流水 + 余额，同一个事务 =====
        // ★以前是"先 insert 打卡记录（自动提交）→ 再调积分事务"：
        //   中间任何一步失败都会留下"有打卡记录、没有积分"的脏数据；
        //   用户重试又会被 uk_user_date 判成"今天已打卡"，这 10 分永远补不回来。
        boolean granted = checkinTxWriter.checkinAndGrant(userId, today, continuousDays, CHECKIN_POINTS);

        // ===== 第 4 步：事务提交后才写位图 =====
        // 位图是可重建的缓存，写失败只删 key，下一次 ensureBitmap 会从 DB 重建。
        // 绝不能因为 Redis 抖动，把一次已经成功的打卡回报成失败。
        try {
            redis.opsForValue().setBit(bitmapKey, todayOffset, true);
        } catch (Exception e) {
            redis.delete(bitmapKey);
            log.error("打卡成功但位图写入失败，已删除位图等待下次重建: userId={}", userId, e);
        }

        // ===== 第 5 步：事务成功后才动榜和发通知 =====
        // 榜和通知都是旁路：失败不能让用户看到"打卡失败"，否则他重试只会得到
        // "今天已打卡"这个更差的结果。数据库已经成功，日志留给对账。
        if (granted) {
            try {
                redis.opsForZSet().incrementScore(
                        RedisKeyConstants.rankMonth(today.format(MONTH_FMT)),
                        String.valueOf(userId), CHECKIN_POINTS);
            } catch (Exception e) {
                log.error("打卡成功但月榜加分失败，等待后续对账: userId={}", userId, e);
            }
            notifyPublisher.publishPointNotify(userId, "打卡成功",
                    "今日打卡 +" + CHECKIN_POINTS + " 积分，已连续打卡 " + continuousDays + " 天。");
        } else {
            // 正常第二次打卡会在事务里的 uk_user_date 直接报"今天已打卡"。
            // 走到这里说明是历史数据里的边缘情况：积分流水已经存在、打卡明细缺失；
            // 现在明细补上了，但今天不再重复加分。
            log.warn("打卡明细已写入，但今天的积分流水已存在，本次不重复加分: userId={}", userId);
        }

        MemberPoints latest = memberPointsMapper.selectById(userId);
        return new CheckinResultVO(granted ? CHECKIN_POINTS : 0, continuousDays,
                latest == null ? 0 : latest.getBalance());
    }

    @Override
    public CheckinCalendarVO calendar(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        // ★直接读 DB 而不是逐天 GETBIT：一次 uk_user_date 上的范围查询最多 31 行，
        //   比"循环 31 次 Redis 往返"便宜，而且 Redis 被清空时日历不会变成一片空白
        List<CheckinRecord> records = checkinRecordMapper.selectList(new LambdaQueryWrapper<CheckinRecord>()
                .eq(CheckinRecord::getUserId, userId)
                .between(CheckinRecord::getCheckDate, monthStart, monthEnd)
                .orderByAsc(CheckinRecord::getCheckDate));

        List<String> days = new ArrayList<>();
        for (CheckinRecord record : records) {
            days.add(record.getCheckDate().format(DAY_FMT));
        }

        MemberPoints member = memberPointsMapper.selectById(userId);
        Long total = checkinRecordMapper.selectCount(new LambdaQueryWrapper<CheckinRecord>()
                .eq(CheckinRecord::getUserId, userId));
        return CheckinCalendarVO.builder()
                .days(days)
                .continuousDays(member == null ? 0 : member.getContinuousDays())
                // ★累计打卡天数取 DB 的行数，不取 t_member_points.total_checkin：
                //   那一列会被"完课加分"之类不该动它的写路径带偏（历史遗留），
                //   而打卡明细表里的行数永远是准的
                .totalDays(total == null ? 0 : total.intValue())
                .build();
    }

    @Override
    public List<PointsLogVO> pointsLog(Long userId) {
        return pointsLogMapper.selectList(new LambdaQueryWrapper<PointsLog>()
                        .eq(PointsLog::getUserId, userId)
                        .orderByDesc(PointsLog::getId)
                        .last("LIMIT 50"))
                .stream()
                .map(p -> PointsLogVO.builder()
                        .id(p.getId())
                        .title(p.getTitle())
                        .points(p.getPoints())
                        .time(p.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                        .build())
                .toList();
    }

    @Override
    public List<PointBriefDTO> brief(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return memberPointsMapper.selectBatchIds(userIds).stream()
                .map(m -> PointBriefDTO.builder()
                        .userId(m.getUserId())
                        .balance(m.getBalance())
                        .totalCheckin(m.getTotalCheckin())
                        .continuousDays(m.getContinuousDays())
                        .build())
                .toList();
    }

    // =====================================================================
    // 位图工具
    // =====================================================================

    /**
     * 位图不存在时用 DB 明细补一次（"可重建"这句话的落点）。
     *
     * <p>★什么情况下会走到这里：Redis 被清、换实例、或者这个用户的历史打卡早于位图功能。
     * 不补的后果不是错数据，而是<b>连续天数从 0 重新开始</b>——用户明明连了 40 天，
     * 打完今天显示"连续 1 天"。这是那种"没人报错、但用户一定发现"的错。</p>
     *
     * <p>只补最近 {@code MAX_CONTINUOUS_SCAN} 天，更早的位对本次计算没有影响，
     * 而全表补会让一个长期用户的重建变成几千次 SETBIT。</p>
     */
    private void ensureBitmap(Long userId, String bitmapKey) {
        if (Boolean.TRUE.equals(redis.hasKey(bitmapKey))) {
            return;
        }
        // ★下限不能低于基准日：offset = 距基准日的天数，早于基准日的日期会算出<b>负 offset</b>，
        //   SETBIT 收到负偏移直接报错。400 天窗口在 2026-01-01 之后头一年多里一定会越界，
        //   所以这里取两者较晚的那天
        LocalDate floor = LocalDate.now().minusDays(MAX_CONTINUOUS_SCAN);
        LocalDate from = floor.isBefore(BITMAP_BASELINE) ? BITMAP_BASELINE : floor;
        List<CheckinRecord> records = checkinRecordMapper.selectList(new LambdaQueryWrapper<CheckinRecord>()
                .eq(CheckinRecord::getUserId, userId)
                .ge(CheckinRecord::getCheckDate, from));
        for (CheckinRecord record : records) {
            redis.opsForValue().setBit(bitmapKey, offsetOf(record.getCheckDate()), true);
        }
        if (!records.isEmpty()) {
            log.info("打卡位图已从 DB 重建: userId={}, 补了 {} 天", userId, records.size());
        }
    }

    /** offset = 该日期距基准日的天数。★基准日一旦定下就不能改（类注释里有说明） */
    private long offsetOf(LocalDate date) {
        return ChronoUnit.DAYS.between(BITMAP_BASELINE, date);
    }

    /**
     * 从<b>昨天</b>的位往低数连续 1 的个数。
     *
     * <p>★为什么从昨天开始：今天要等事务提交才算打卡成功，先把今天算进去会让
     * "事务失败但连续天数已经 +1"。调用方最后会再 +1，得到包含今天的连续天数。</p>
     *
     * <p>★为什么用逐位 GETBIT 而不是一条 BITFIELD：位图跨度按"距基准日天数"算，
     * 想一次取 64 位就得处理 offset 不对齐的问题（跨两个 64 位块要拼两次），
     * 而正常情况下连续天数只会数几十位就断。这个函数在"读到 0"就停，
     * 最坏情况由 {@code MAX_CONTINUOUS_SCAN} 兜住。</p>
     */
    private int countContinuousBefore(String bitmapKey, long todayOffset) {
        int continuous = 0;
        long offset = todayOffset - 1;
        while (continuous < MAX_CONTINUOUS_SCAN && offset >= 0) {
            if (!Boolean.TRUE.equals(redis.opsForValue().getBit(bitmapKey, offset))) {
                break;
            }
            continuous++;
            offset--;
        }
        return continuous;
    }
}
