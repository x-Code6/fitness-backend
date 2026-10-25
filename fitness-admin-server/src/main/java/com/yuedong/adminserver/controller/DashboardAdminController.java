package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.model.vo.DashboardVO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.AdminCountDTO;
import com.yuedong.order.api.client.BookingClient;
import com.yuedong.order.api.dto.AdminStatDTO;
import com.yuedong.user.api.client.UserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class DashboardAdminController {

    private final BookingClient orderClient;
    private final CourseAdminClient courseClient;
    private final UserClient userClient;

    @GetMapping("/summary")
    public Result<DashboardVO> summary() {
        // ===== 第 1 步：订单侧（成交、时段分子、凭证、热销与趋势）=====
        AdminStatDTO stat = callOrDefault("order 统计", () -> orderClient.adminStat().getData(), null);
        // ===== 第 2 步：课程侧（在售课、教练、门店、时段分母）=====
        AdminCountDTO counts = callOrDefault("course 计数", () -> courseClient.adminCount().getData(), null);
        // ===== 第 3 步：会员数 =====
        Long memberCount = callOrDefault("user 会员数", () -> userClient.memberCount().getData(), 0L);

        return Result.ok(DashboardVO.builder()
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
                .build());
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
}
