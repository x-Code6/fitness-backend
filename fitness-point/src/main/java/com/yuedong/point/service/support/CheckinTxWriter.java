package com.yuedong.point.service.support;

import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.point.mapper.CheckinRecordMapper;
import com.yuedong.point.model.entity.CheckinRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 打卡的事务边界：<b>打卡明细 + 积分流水 + 余额</b>，要么都成功，要么都回滚。
 *
 * <p>★为什么单独一个 Bean，而不是在 {@link com.yuedong.point.service.impl.CheckinServiceImpl}
 * 里直接写 {@code @Transactional}：Spring 事务靠代理生效，同一个类里
 * {@code this.xxx()} 自调用不走代理，注解写了等于没写。这和 {@link PointsTxWriter}
 * 单独拆出来是同一个原因。</p>
 *
 * <p>★幂等键必须带 {@code userId}：积分流水表的唯一键是
 * {@code uk_biz(biz_type, biz_id)}，它不包含 {@code user_id}。如果 {@code biz_id}
 * 只写日期，全站第二个用户当天打卡就会撞唯一键，积分永远加不上。</p>
 */
@Service
@RequiredArgsConstructor
public class CheckinTxWriter {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CheckinRecordMapper checkinRecordMapper;
    private final PointsTxWriter pointsTxWriter;

    /**
     * 写打卡明细并加积分。
     *
     * @param userId         打卡人
     * @param today          打卡日期
     * @param continuousDays 含今天的连续打卡天数（调用方已经 +1）
     * @param points         本次打卡积分
     * @return true=积分新入账；false=这个用户的今天已经入过账（通常是历史脏数据）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean checkinAndGrant(Long userId, LocalDate today, int continuousDays, int points) {
        // 增：写打卡明细。撞 uk_user_date 就说明今天已经打过卡；
        // 这是并发双击的最终防线，比 Redis 的 GETBIT/SETBIT 更可靠。
        CheckinRecord record = new CheckinRecord();
        record.setUserId(userId);
        record.setCheckDate(today);
        record.setCreatedAt(LocalDateTime.now());
        try {
            checkinRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CHECKIN_DUPLICATE);
        }

        // 增：积分流水 + 改余额。bizId 必须带 userId，理由见类注释。
        String bizId = userId + ":" + today.format(DAY_FMT);
        return pointsTxWriter.grant(userId, points, continuousDays, "CHECKIN", bizId, "每日打卡");
    }
}
