package com.yuedong.adminserver.service.impl;

import com.yuedong.adminserver.model.vo.DashboardVO;
import com.yuedong.adminserver.service.DashboardService;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.AdminCountDTO;
import com.yuedong.order.api.client.BookingClient;
import com.yuedong.order.api.dto.AdminStatDTO;
import com.yuedong.user.api.client.UserClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 数据看板：三个服务的数字拼一页。
 *
 * <p>★一次请求而不是让前端发三次：前端拿不到"跨服务的部分失败"这种语义，
 * 三个请求各自 loading 会让页面跳三次。BFF 把它收成一次。</p>
 *
 * <p>★<b>每一路都单独降级</b>：user 服务挂了不该让整个白屏，
 * 那一格显示 0 并在日志里留痕就够了。看板上少一个数字是体验问题，
 * 看板打不开是故障。</p>
 */
@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

    /** 看板 BFF 的单路取数上限：超时就按默认值降级，不能把整页拖死 */
    private static final long BFF_TIMEOUT_SECONDS = 3;

    private final BookingClient orderClient;
    private final CourseAdminClient courseClient;
    private final UserClient userClient;
    private final ExecutorService dashboardExecutor;

    public DashboardServiceImpl(BookingClient orderClient,
                                CourseAdminClient courseClient,
                                UserClient userClient,
                                @Qualifier("dashboardExecutor") ExecutorService dashboardExecutor) {
        this.orderClient = orderClient;
        this.courseClient = courseClient;
        this.userClient = userClient;
        this.dashboardExecutor = dashboardExecutor;
    }

    @Override
    public DashboardVO summary() {
        // ===== 三路互不依赖，并行取数；每一路各自降级 =====
        CompletableFuture<AdminStatDTO> statFuture =
                asyncOrDefault("order 统计", () -> orderClient.adminStat().getData(), null);
        CompletableFuture<AdminCountDTO> countsFuture =
                asyncOrDefault("course 计数", () -> courseClient.adminCount().getData(), null);
        CompletableFuture<Long> memberFuture =
                asyncOrDefault("user 会员数", () -> userClient.memberCount().getData(), 0L);

        // 每个 future 都自带 fallback，allOf 只负责等齐；不会因为一路失败把整页打挂
        CompletableFuture.allOf(statFuture, countsFuture, memberFuture).join();

        AdminStatDTO stat = statFuture.join();
        AdminCountDTO counts = countsFuture.join();
        Long memberCount = memberFuture.join();

        return DashboardVO.builder()
                .todayOrderCount(stat == null ? 0 : stat.getTodayOrderCount())
                .todayAmount(stat == null ? null : stat.getTodayAmount())
                .refundedAmount(stat == null ? null : stat.getRefundedAmount())
                .todaySlotTotal(counts == null ? 0 : counts.getTodaySlotTotal())
                .todaySlotUsed(stat == null ? 0 : stat.getTodaySlotUsed())
                .upcoming(stat == null ? 0 : stat.getUpcomingCount())
                .memberCount(memberCount == null ? 0 : memberCount.intValue())
                .activeEntitlement(stat == null ? 0 : stat.getActiveEntitlement())
                .expiringSoon(stat == null ? 0 : stat.getExpiringSoon())
                .courseCount(counts == null ? 0 : counts.getCourseCount())
                .coachCount(counts == null ? 0 : counts.getCoachCount())
                .hotCourses(stat == null || stat.getHotCourses() == null ? List.of() : stat.getHotCourses())
                .trend(stat == null || stat.getTrend() == null ? List.of() : stat.getTrend())
                .statusDist(stat == null || stat.getStatusDist() == null ? List.of() : stat.getStatusDist())
                .build();
    }

    /**
     * 一路取数失败不影响其他路：吃掉异常、记日志、返回默认值。
     *
     * <p>★这个"降级"只用在<b>只读统计</b>上。写操作绝不能这么包——
     * 把"取消订单失败"咽下去返回成功，就是数据事故。
     * 判断标准是这句话：失败了用户看到的是"少一个数字"还是"事情没做成但以为做成了"？
     * 前者可以降级，后者必须抛。</p>
     */
    private <T> T callOrDefault(String what, Supplier<T> call, T fallback) {
        try {
            T value = call.get();
            return value == null ? fallback : value;
        } catch (Exception e) {
            log.warn("看板取数失败，该项使用默认值: {}", what, e);
            return fallback;
        }
    }

    /**
     * 异步取数 + 超时降级。
     *
     * <p>★用专用线程池而不是 commonPool：Feign 是阻塞调用。
     * 提交失败（线程池已关闭/拒绝）时同步返回 fallback，不能让看板 500。</p>
     */
    private <T> CompletableFuture<T> asyncOrDefault(String what, Supplier<T> call, T fallback) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> callOrDefault(what, call, fallback), dashboardExecutor)
                    .completeOnTimeout(fallback, BFF_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(e -> {
                        log.warn("看板取数超时，该项使用默认值: {}", what, e);
                        return fallback;
                    });
        } catch (Exception e) {
            // 线程池已关闭/拒绝：同步降级，保证接口仍能返回
            log.warn("看板取数任务提交失败，该项使用默认值: {}", what, e);
            return CompletableFuture.completedFuture(fallback);
        }
    }
}
