package com.yuedong.point.task;

import com.yuedong.point.service.RankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 月榜归档任务（D17）：每月 1 日 00:10 把上一个自然月的实时榜写进 {@code t_rank_archive}。
 *
 * <p>★<b>00:10 而不是 00:00</b>：00:00 是"当月"与"上月"的分界，
 * 那一刻还可能有余下的打卡请求在往旧 key 上 ZINCRBY（时钟、重试、排队）。
 * 让出十分钟，等旧 key 彻底静下来再搬。</p>
 *
 * <p>★<b>不需要分布式锁</b>：归档写入是 {@code INSERT ... ON DUPLICATE KEY UPDATE}，
 * 主键 {@code (season, user_id)}，重跑就是把同一批数据再覆盖一遍。
 * 本项目给定时任务加锁的判据只有一条——<b>写操作自己带不带条件</b>：
 * 带唯一键或状态 CAS 的不用锁，什么都不带的才需要。</p>
 *
 * <p>⚠️ 依赖"当月 1 日 00:10 时上个月的 ZSet 还在 Redis 里"。
 * 实时榜 key 本来不设 TTL（见 RedisKeyConstants），所以这个前提成立；
 * 万一 Redis 被清过，那个月就归档不到（返回 0 只记一条 info 日志），
 * 补法是"从 {@code t_points_log} 按月份重算"——一期没写这个回填任务，
 * 因为它是"故障才用得上"的代码，写出来也没人验证过。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankArchiveTask {

    private final RankService rankService;

    /** 每月 1 日 00:10 执行 */
    @Scheduled(cron = "0 10 0 1 * ?")
    public void archive() {
        try {
            int archived = rankService.archiveLastMonth();
            if (archived > 0) {
                log.info("月榜归档任务完成，共 {} 人", archived);
            }
        } catch (Exception e) {
            // 任务内部吞异常但留痕：这一轮失败下一轮还能重跑（写入是 upsert，重跑安全）
            log.error("月榜归档失败，下月同一时间会再试一次", e);
        }
    }
}
