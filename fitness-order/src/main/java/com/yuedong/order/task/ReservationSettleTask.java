package com.yuedong.order.task;

import com.yuedong.order.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预约到点结账任务（每分钟）。
 *
 * <p>做一件事：{@code end_time} 已过但仍是 BOOKED 的预约 → 置 FINISHED 并扣掉凭证一次。
 * ★"取消不扣次"这条规则在这里<b>不需要写代码</b>：取消的单状态是 CANCELED，
 * 永远进不了扫描条件，所以不可能被扣。规则由查询条件天然保证，
 * 比"写一段 if 判断这是不是取消的单"可靠得多。</p>
 *
 * <p>★为什么每分钟而不是延迟消息：项目里已经有 15 分钟超时取消用延迟消息，
 * 这里再学一遍没有新信息，而结账的语义是"到点之后随便什么时候算都行"，
 * 轮询反而更稳（消息丢了就永远不结账，任务下一分钟还会跑）。
 * <b>两种机制选哪种，看的是"丢了会不会永久错"，不是哪个更高级。</b></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationSettleTask {

    private final ReservationService reservationService;

    /** 每分钟第 0 秒。★带状态条件的 CAS 让"重跑"和"多实例同时跑"都安全，所以不加锁 */
    @Scheduled(cron = "0 * * * * ?")
    public void scan() {
        try {
            reservationService.settleDue();
        } catch (Exception e) {
            // 吞异常只留日志：这一轮没结完的账下一轮还会被同一条查询捞出来，
            // 但异常必须留痕，否则"用户的次数莫名少了一次"无从查起
            log.error("预约到点结账失败，下一分钟重试", e);
        }
    }
}
