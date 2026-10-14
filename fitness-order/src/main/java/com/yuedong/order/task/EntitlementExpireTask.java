package com.yuedong.order.task;

import com.yuedong.order.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 凭证过期扫描任务（流程 F，每小时一次）。
 *
 * <p>做两件事：过期的 ACTIVE 凭证置 EXPIRED；其中<b>一次都没用过</b>的把订单推到 REFUNDED
 * （D7：用过一点的剩余不退）。</p>
 *
 * <p>★为什么"重跑安全"不需要分布式锁（docs 4.7）：所有状态变更都是带状态条件的
 * 原子 UPDATE（CAS），第二个实例或重跑的这一轮影响行数为 0，什么都不做。
 * 所以本项目不引入 ShedLock —— 那是给"没有状态条件"的任务准备的。</p>
 *
 * <p>★为什么 1 小时而不是每分钟：过期是<b>天级</b>事件，精度到小时用户完全无感，
 * 而每分钟扫一遍 {@code idx_expire} 是白给的 DB 压力。任务频率要按"事件本身的时间粒度"定，
 * 不是越勤快越安全。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntitlementExpireTask {

    private final EntitlementService entitlementService;

    /**
     * 每小时第 20 分执行。
     * <p>错开整点是为了不和秒杀扫描（30 分钟一轮）撞在同一秒上——
     * 演示环境无所谓，但这个习惯到了多任务并发的真实环境能省一次排查。</p>
     */
    @Scheduled(cron = "0 20 * * * ?")
    public void scan() {
        try {
            int processed = entitlementService.expireOverdue();
            if (processed > 0) {
                log.info("凭证过期任务本轮处理 {} 张", processed);
            }
        } catch (Exception e) {
            // ★任务内部吞异常：@Scheduled 的方法抛出异常只会让这一轮失败，
            // 但日志必须留痕；下一轮还会重试，所以不阻断调度线程也不掩盖问题
            log.error("凭证过期扫描失败，下一轮重试", e);
        }
    }
}
