package com.yuedong.course.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.course.cache.CourseDetailCacheEvictor;
import com.yuedong.course.mapper.SeckillActivityMapper;
import com.yuedong.course.model.entity.SeckillActivity;
import com.yuedong.course.service.SeckillActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动扫描任务：库存 key 补偿 + 活动到点状态流转。
 *
 * <p>调度与业务实现分离，service 只保留业务方法；
 * 多实例部署下每台都会跑，状态流转依赖 Mapper 的 CAS 保证只生效一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillActivityScanTask {

    private final SeckillActivityMapper activityMapper;
    private final SeckillActivityService seckillActivityService;
    private final CourseDetailCacheEvictor courseDetailCacheEvictor;

    /**
     * 补偿进行中活动的库存 key。
     *
     * <p>Redis 重启/被清后，status=1 的活动不会再次走"到点开始"的预热流程，
     * 库存 key 不存在时 Lua 会把缺键当作 0，整个活动剩余时间全部提示售罄。
     * 这里每分钟扫一次进行中的活动，只补不存在的 key；已存在的不覆盖。</p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void repairActiveStock() {
        List<SeckillActivity> active = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 1));
        for (SeckillActivity activity : active) {
            seckillActivityService.preheat(activity.getId());
        }
    }

    /**
     * 每 30 秒扫描一次活动表：到点开始（先预热再翻状态）→ 进行中；到点结束 → 已结束。
     *
     * <p>三个设计点：</p>
     * <ol>
     *   <li><b>先预热、后翻状态</b>，避免"已开始但 Redis 没有库存"的空窗；</li>
     *   <li><b>翻状态用 CAS</b>（{@code WHERE status = 0}），多实例下只有一台能生效，
     *       不引入分布式锁；</li>
     *   <li>查询条件是 {@code start_time <= now}，错过的活动下一轮扫描会补发。</li>
     * </ol>
     */
    @Scheduled(fixedDelay = 30_000)
    public void scanAndSwitch() {
        LocalDateTime now = LocalDateTime.now();

        // ===== 到点（或已过点）的开始：先预热库存，再 CAS 翻状态 =====
        List<SeckillActivity> toStart = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 0)
                        .le(SeckillActivity::getStartTime, now));
        for (SeckillActivity activity : toStart) {
            if (!seckillActivityService.preheat(activity.getId())) {
                continue;
            }
            // 只有把状态从 0 改成 1 的那一次才算"发放成功"
            if (activityMapper.markRunning(activity.getId(), now) > 0) {
                // 详情缓存里可能还没有秒杀入口/秒杀价，开始时要让它重新组装
                courseDetailCacheEvictor.evict(activity.getCourseId());
                log.info("秒杀活动已发放: id={}, courseId={}, 原定开始时间={}",
                        activity.getId(), activity.getCourseId(), activity.getStartTime());
            }
        }

        // ===== 到点结束：CAS 翻状态。Redis key 留给 TTL 自然过期 =====
        List<SeckillActivity> toEnd = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 1)
                        .le(SeckillActivity::getEndTime, now));
        for (SeckillActivity activity : toEnd) {
            if (activityMapper.markEnded(activity.getId()) > 0) {
                // 结束后详情页不能再显示秒杀价/抢购按钮
                courseDetailCacheEvictor.evict(activity.getCourseId());
                log.info("秒杀活动已结束: id={}", activity.getId());
            }
        }
    }
}
