package com.yuedong.point.task;

import com.yuedong.point.service.RankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 月榜归档任务（D17）：每天 00:10 重算<b>上一个自然月</b>的榜并写进 {@code t_rank_archive}。
 *
 * <p>★为什么是每天而不是每月 1 日：归档现在从 {@code t_points_log} 重算，
 * 同一个 season 重复 upsert 是安全的。每天跑一遍，等于给上一个月的任务
 * <b>一整个月的重试窗口</b>；某天失败，第二天还会补，不会等到下个月才“误归档”另一个月。</p>
 *
 * <p>★00:10 而不是 00:00：00:00 是月份分界，可能还有跨零点的打卡/完课流水刚落到本库，
 * 让出十分钟等它们静下来。</p>
 *
 * <p>★不需要分布式锁：写入是 {@code INSERT ... ON DUPLICATE KEY UPDATE}，
 * 主键 {@code (season, user_id)}，重复执行就是覆盖同一批数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankArchiveTask {

    private final RankService rankService;

    /** 每天 00:10 重算上一个自然月；重复执行安全 */
    @Scheduled(cron = "0 10 0 * * ?")
    public void archive() {
        try {
            int archived = rankService.archiveLastMonth();
            if (archived > 0) {
                log.info("月榜归档任务完成，共 {} 人", archived);
            }
        } catch (Exception e) {
            // 今天失败明天继续；流水还在，重算不会丢数据
            log.error("月榜归档失败，明天继续重试", e);
        }
    }
}
