package com.yuedong.point.service;

import com.yuedong.point.mapper.MemberPointsMapper;
import com.yuedong.point.mapper.PointsLogMapper;
import com.yuedong.point.model.entity.MemberPoints;
import com.yuedong.point.model.entity.PointsLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 积分入账的<b>事务边界</b>：汇总行自增 + 流水插入，两条语句要么都成要么都不成。
 *
 * <p>★为什么单独一个类，而不是把 {@code @Transactional} 打在
 * {@code CheckinServiceImpl.doCheckin} 里的那个私有方法上：
 * Spring 的声明式事务靠<b>代理</b>实现，同一个类里 {@code this.grant(...)} 不走代理，
 * 注解写了等于没写（这是最经典的一个静默失效坑，编译器和启动都不报错，
 * 只有回滚发生时才知道）。要么自注入、要么拆一个 Bean——
 * 拆 Bean 最直白，也多一个"事务边界在哪"的显式位置。</p>
 *
 * <p>★这里<b>不包含</b> Redis 的 ZINCRBY：榜是缓存，账户是钱。
 * 把缓存写进事务里，就等于让"Redis 抖动" capable 回滚一次真实加分。</p>
 */
@Service
@RequiredArgsConstructor
public class PointsTxWriter {

    private final MemberPointsMapper memberPointsMapper;
    private final PointsLogMapper pointsLogMapper;

    /**
     * @param continuousDays 本次算出来的连续天数，写进汇总行的展示列
     * @param bizType        CHECKIN / COURSE_FINISHED / COMMUNITY_PUBLISH…
     * @param bizId          业务键。<b>流水表的 {@code uk_biz(biz_type,biz_id)} 就是幂等键</b>：
     *                       打卡用日期串、完课用 {@code userId:videoId}（docs P0-3）
     * @return true=流水新写入；false=这个 bizId 已经记过（调用方据此决定是否给榜加分）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean grant(Long userId, int points, int continuousDays,
                         String bizType, String bizId, String title) {
        // ===== 第 1 步：流水先插。撞 uk_biz 就说明这笔业务已经记过分，直接整体放弃 =====
        // ★顺序是"先插流水、后加余额"而不是反过来：
        //   如果流水重复而余额先加了，就会留下"钱多了但没有凭证"的账——那正是查不清的那种错
        PointsLog logRow = new PointsLog();
        logRow.setUserId(userId);
        logRow.setTitle(title);
        logRow.setPoints(points);
        logRow.setBizType(bizType);
        logRow.setBizId(bizId);
        logRow.setCreatedAt(LocalDateTime.now());
        try {
            pointsLogMapper.insert(logRow);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }

        // ===== 第 2 步：汇总行（无则建，有则原子自增）=====
        // ★CHECKIN 与非 CHECKIN 走两条不同的 UPDATE：
        //   addCheckinPoints 会连带 total_checkin + 1 并覆写 continuous_days，
        //   完课/发动态那种事件如果也调它，就会出现"上了一节课、打卡数涨一天"的错账。
        //   这两条语句的差别只在业务含义上，SQL 差一个 SET 子句，所以必须显式分叉而不是复用
        boolean isCheckin = "CHECKIN".equals(bizType);
        MemberPoints member = memberPointsMapper.selectById(userId);
        if (member == null) {
            MemberPoints init = new MemberPoints();
            init.setUserId(userId);
            init.setBalance(points);
            init.setTotalCheckin(isCheckin ? 1 : 0);
            init.setContinuousDays(isCheckin ? continuousDays : 0);
            init.setUpdatedAt(LocalDateTime.now());
            try {
                memberPointsMapper.insert(init);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首次入账：另一个请求刚建行，这一次落到自增分支
                addExisting(userId, points, continuousDays, isCheckin);
            }
        } else {
            addExisting(userId, points, continuousDays, isCheckin);
        }
        return true;
    }

    private void addExisting(Long userId, int points, int continuousDays, boolean isCheckin) {
        if (isCheckin) {
            memberPointsMapper.addCheckinPoints(userId, points, continuousDays);
        } else {
            memberPointsMapper.addEventPoints(userId, points);
        }
    }
}
