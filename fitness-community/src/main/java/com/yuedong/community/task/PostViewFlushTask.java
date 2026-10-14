package com.yuedong.community.task;

import com.yuedong.community.service.PostViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 浏览量刷库任务：把 Redis 里的增量原子取出、入账到 {@code t_post.view_count}。
 *
 * <p>★它是"三层模型"里唯一碰 DB 的那一层（docs 4.12 K1）：
 * 请求路径只做 SADD + HINCRBY + Kafka send，一次 UPDATE 都不发；
 * 这里把攒着的增量按动态合并成一条 {@code view_count = view_count + delta}，
 * 一万次浏览在 DB 里只是一行更新，而不是一万行 UPDATE。</p>
 *
 * <p>★30 秒这个频率的取法：它只决定"用户看到的数字最长滞后多久"和"DB 每秒几次写"，
 * 30 秒足够让体感不掉（详情页显示的浏览量还叠了未入账的增量，本来就不是纯读 DB），
 * 又不至于像每分钟四次那样把空闲的库敲个不停。
 * 真正的取舍是"刷得越勤 → Redis 里滞留的未入账增量越小 → Redis 挂掉时丢得越少"，
 * 30 秒一轮意味着最坏丢 30 秒的浏览，这个代价业务上可接受（用户明确同意短暂不一致）。</p>
 *
 * <p>⚠️ <b>单实例前提</b>（本项目就是这样部署的）：一轮任务先读 Hash 快照、再按快照减量。
 * 同一个进程里 {@code fixedDelay} 保证两轮不重叠，所以安全。
 * 但如果<b>起两个实例</b>，两边读到的快照一样、各自减一次、各自入账一次 → 浏览量会翻倍。
 * 这里不写"重跑安全"的场面话，也不提前上 ShedLock：真要横向扩容，
 * 改法是"按 postId 取模分片"或"整个 Hash 用 rename 原子换走再刷"，
 * 那时再选锁。多实例的假安全注释比没注释更坏。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostViewFlushTask {

    private final PostViewService postViewService;

    /**
     * 固定间隔 30 秒（{@code fixedDelay}：上一轮<b>跑完</b>再计时，不是每 30 秒并发一次）。
     * <p>★选 fixedDelay 而不是 fixedRate 的原因：刷库要逐条 UPDATE，
     * 万一某一轮卡住（DB 慢、Redis 慢），fixedRate 会让下一轮照旧起、两轮叠在一起同时减同一个 Hash，
     * 那时就不是"一个实例"而是"同进程里的两个任务互抢"了。fixedDelay 天然串行。</p>
     */
    @Scheduled(fixedDelayString = "${community.view-flush-delay-ms:30000}")
    public void flush() {
        try {
            int flushed = postViewService.flushToDb();
            if (flushed > 0) {
                log.info("浏览量入账 {} 条动态", flushed);
            }
        } catch (Exception e) {
            // ★吞异常但留痕：这一轮失败不要打断调度线程，增量还留在 Redis 里，下一轮会一起刷掉
            log.error("浏览量刷库失败，下一轮重试", e);
        }
    }
}
