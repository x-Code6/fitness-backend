package com.yuedong.order.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端看板的<b>订单侧</b>统计（其余三块由 course / user 服务各给一份，admin-server 拼）。
 *
 * <p>★为什么统计由各服务自己算、BFF 只做加法：
 * "今天成交多少"这件事的判据（哪些状态算成交、退款怎么扣）属于订单域的业务规则，
 * 搬到 BFF 里就变成了"两个服务都能定义成交"，一改口径就得同时改两处。
 * BFF 的职责只有聚合与补名字，<b>不含业务判断</b>。</p>
 *
 * <p>★所有数字都是<b>实时算</b>的，没有落汇总表：一期数据量小，一条带索引的
 * {@code count/sum} 足够。真到量再上"按天汇总表 + 定时任务"，
 * 那时这个 DTO 的字段不变，只是数据来源换了——契约稳定、实现可换。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminStatDTO {

    /** 今日下单数（含未支付：运营要看"下了多少单"） */
    private Integer todayOrderCount;

    /** 今日实付合计（只算 PAID，口径写在 SQL 的 status 条件里） */
    private BigDecimal todayAmount;

    /** 累计退款金额（REFUNDED 单的实付合计） */
    private BigDecimal refundedAmount;

    /** 今日已被占用的时段格子数 */
    private Integer todaySlotUsed;

    /** 待上课的预约张数（status=BOOKED 且还没到 end_time） */
    private Integer upcomingCount;

    /** 在用的凭证张数（ACTIVE） */
    private Integer activeEntitlement;

    /** 7 天内到期的凭证张数（催用是运营最常做的事，所以单独给一个数而不是让人去列表里翻） */
    private Integer expiringSoon;

    /** 近 7 天热销课 Top5 */
    private List<HotCourse> hotCourses;

    /** 近 7 天每日成交趋势 */
    private List<TrendPoint> trend;

    /** 近 7 天订单状态分布（看板环形图：下单后的去向一眼看清） */
    private List<StatusCount> statusDist;

    /** 热销行：只给名字与份数，名字是订单表里的快照，不再回查课程 */
    @Data
    @Builder
    public static class HotCourse {
        private String name;
        private Integer sold;
    }

    /** 状态分布行：status 是订单轴的四个枚举值，count 是该状态的订单数 */
    @Data
    @Builder
    public static class StatusCount {
        private String status;
        private Integer count;
    }

    /** 趋势点：date 已经格式化成 MM-dd（看板横轴直接显示它） */
    @Data
    @Builder
    public static class TrendPoint {
        private String date;
        private BigDecimal amount;
        private Integer count;
    }
}
