package com.yuedong.point.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.point.api.dto.PointBriefDTO;
import com.yuedong.point.mapper.MemberPointsMapper;
import com.yuedong.point.mapper.PointsLogMapper;
import com.yuedong.point.model.entity.MemberPoints;
import com.yuedong.point.model.entity.PointsLog;
import com.yuedong.point.model.vo.CheckinCalendarVO;
import com.yuedong.point.model.vo.CheckinResultVO;
import com.yuedong.point.model.vo.CheckinTodayVO;
import com.yuedong.point.model.vo.PointsLogVO;
import com.yuedong.point.service.CheckinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 打卡与积分实现。
 *
 * <p>打卡为什么用 Bitmap：一个月一个用户的打卡状态只需要 31 个 bit（4 字节），
 * 亿级用户一年也只占几百 MB；GETBIT/SETBIT/BITFIELD 都是 O(1) 内存操作。
 * key 设计：point:check:{yyyyMM}:{userId}，offset = 日 - 1。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinServiceImpl implements CheckinService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int CHECKIN_POINTS = 10;

    private final StringRedisTemplate redis;
    private final MemberPointsMapper memberPointsMapper;
    private final PointsLogMapper pointsLogMapper;
    private final com.yuedong.point.mq.NotifyPublisher notifyPublisher;

    @Override
    public CheckinTodayVO today(Long userId) {
        LocalDate today = LocalDate.now();
        String key = monthKey(today, userId);
        Boolean bit = redis.opsForValue().getBit(key, today.getDayOfMonth() - 1);
        MemberPoints member = memberPointsMapper.selectById(userId);
        return CheckinTodayVO.builder()
                .checked(Boolean.TRUE.equals(bit))
                .continuousDays(member == null ? 0 : member.getContinuousDays())
                .build();
    }

    @Override
    public CheckinResultVO doCheckin(Long userId) {
        LocalDate today = LocalDate.now();
        String key = monthKey(today, userId);
        long offset = today.getDayOfMonth() - 1L; // Bitmap offset 从 0 开始：1 号 → 0

        // ===== 第 1 步：GETBIT 判重（今天已打卡直接拒绝） =====
        if (Boolean.TRUE.equals(redis.opsForValue().getBit(key, offset))) {
            throw new BusinessException(ErrorCode.CHECKIN_DUPLICATE);
        }

        // ===== 第 2 步：SETBIT 打卡 =====
        redis.opsForValue().setBit(key, offset, true);

        // ===== 第 3 步：BITFIELD 取"从 1 号到今天"的位串，从最低位（今天）数连续 1 的个数 =====
        int continuousDays = countContinuous(key, today.getDayOfMonth());

        // ===== 第 4 步：记账 —— 汇总行（无则建） + 流水（同方法内顺序写，演示项目从简不加分布式事务） =====
        MemberPoints member = memberPointsMapper.selectById(userId);
        if (member == null) {
            // 首次打卡：建汇总行；并发首卡由 user_id 主键兜底（DuplicateKey 后走加积分）
            MemberPoints init = new MemberPoints();
            init.setUserId(userId);
            init.setBalance(CHECKIN_POINTS);
            init.setTotalCheckin(1);
            init.setContinuousDays(continuousDays);
            init.setUpdatedAt(LocalDateTime.now());
            try {
                memberPointsMapper.insert(init);
            } catch (DuplicateKeyException e) {
                memberPointsMapper.addCheckinPoints(userId, CHECKIN_POINTS, continuousDays);
            }
        } else {
            memberPointsMapper.addCheckinPoints(userId, CHECKIN_POINTS, continuousDays);
        }

        PointsLog logRow = new PointsLog();
        logRow.setUserId(userId);
        logRow.setTitle("每日打卡");
        logRow.setPoints(CHECKIN_POINTS);
        logRow.setBizType("CHECKIN");
        logRow.setBizId(today.format(DAY_FMT));
        logRow.setCreatedAt(LocalDateTime.now());
        pointsLogMapper.insert(logRow);

        // ===== 第 5 步：月度排行榜加分（ZSet ZINCRBY，实时榜） =====
        redis.opsForZSet().incrementScore(RedisKeyConstants.rankMonth(today.format(MONTH_FMT)),
                String.valueOf(userId), CHECKIN_POINTS);

        // ===== 第 6 步：站内通知（事件驱动，user 服务消费生成消息） =====
        notifyPublisher.publishPointNotify(userId, "打卡成功",
                "今日打卡 +" + CHECKIN_POINTS + " 积分，已连续打卡 " + continuousDays + " 天。");

        // ===== 第 7 步：回读最新余额返回 =====
        MemberPoints latest = memberPointsMapper.selectById(userId);
        return new CheckinResultVO(CHECKIN_POINTS, continuousDays, latest.getBalance());
    }

    @Override
    public CheckinCalendarVO calendar(Long userId) {
        LocalDate today = LocalDate.now();
        String key = monthKey(today, userId);
        int daysInMonth = today.lengthOfMonth();

        // 逐天 GETBIT 收集当月已打卡日期（最多 31 次内存操作，足够快）
        List<String> days = new ArrayList<>();
        for (int d = 1; d <= today.getDayOfMonth(); d++) {
            if (Boolean.TRUE.equals(redis.opsForValue().getBit(key, d - 1))) {
                days.add(today.format(DAY_FMT).substring(0, 8) + String.format("%02d", d));
            }
        }
        MemberPoints member = memberPointsMapper.selectById(userId);
        return CheckinCalendarVO.builder()
                .days(days)
                .continuousDays(member == null ? 0 : member.getContinuousDays())
                .totalDays(member == null ? 0 : member.getTotalCheckin())
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

    /** BITFIELD 反推连续打卡天数：u{day} 位串的最低位对应今天，从低位往高数连续 1 */
    private int countContinuous(String key, int dayOfMonth) {
        List<Long> values = redis.execute((org.springframework.data.redis.core.RedisCallback<List<Long>>) conn ->
                conn.stringCommands().bitField(key.getBytes(),
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                                .valueAt(0)));
        long bits = (values == null || values.isEmpty() || values.get(0) == null) ? 0 : values.get(0);
        int continuous = 0;
        while (((bits >> continuous) & 1) == 1 && continuous < dayOfMonth) {
            continuous++;
        }
        return continuous;
    }

    /** Bitmap key：point:check:{yyyyMM}:{userId}，一用户一月一 key，offset = 日 - 1 */
    private String monthKey(LocalDate date, Long userId) {
        return RedisKeyConstants.pointCheck(date.format(MONTH_FMT), userId);
    }
}
