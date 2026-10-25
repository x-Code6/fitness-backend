package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.vo.HotCourseRow;
import com.yuedong.order.model.vo.StatusCountRow;
import com.yuedong.order.model.vo.TrendRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单表 Mapper。
 *
 * <p>本接口的重点：所有状态流转都是<b>带状态条件的原子 UPDATE（CAS）</b>，
 * 靠"影响行数是否为 1"判断成败，而不是"先查出来判断再 update"。
 * 后者在读-改-写之间有时间窗，并发下会出现两个请求都认为自己可以流转。</p>
 */
public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 支付：UNPAID → PAID。
     *
     * <p>条件里带 {@code status = 'UNPAID'} 有两个作用：① 重复支付只有第一次生效；
     * ② <b>已取消或已退款的单不可能被支付</b>——这也是 15 分钟超时取消能安全工作的前提
     * （两边抢同一行，谁先改成功谁说了算）。</p>
     *
     * @return 1 支付成功；0 状态不允许（已支付/已取消/不存在）
     */
    @Update("UPDATE t_booking SET status = 'PAID', pay_time = #{now} " +
            "WHERE order_no = #{orderNo} AND user_id = #{userId} AND status = 'UNPAID'")
    int markPaid(@Param("orderNo") String orderNo,
                 @Param("userId") Long userId,
                 @Param("now") LocalDateTime now);

    /**
     * 取消：只有未支付的单能取消（已支付走退款，不是取消）。
     *
     * @return 1 取消成功；0 状态已是 PAID/CANCELED，用户该刷新页面
     */
    @Update("UPDATE t_booking SET status = 'CANCELED', cancel_reason = #{reason}, canceled_at = #{now} " +
            "WHERE order_no = #{orderNo} AND user_id = #{userId} AND status = 'UNPAID'")
    int cancel(@Param("orderNo") String orderNo,
               @Param("userId") Long userId,
               @Param("reason") String reason,
               @Param("now") LocalDateTime now);

    /**
     * 超时未支付自动取消：与 {@link #cancel} 的区别是<b>不校验 user_id</b>（MQ 消费场景）。
     *
     * <p>★条件仍只有 {@code status='UNPAID'}，所以"用户已经支付完了，延迟消息才到"这种情况
     * 影响行数为 0，什么都不做。这就是 RabbitMQ 延迟消息不需要"撤回"的实现方式：
     * <b>消费时用状态判等，比撤回消息简单且不会出错</b>。</p>
     */
    @Update("UPDATE t_booking SET status = 'CANCELED', cancel_reason = '超时未支付，自动取消', " +
            "canceled_at = #{now} WHERE order_no = #{orderNo} AND status = 'UNPAID'")
    int cancelIfUnpaid(@Param("orderNo") String orderNo, @Param("now") LocalDateTime now);

    /**
     * 扫超时未支付订单（延迟消息发送失败时的兜底）。
     * <p>★只负责查出来，真正的取消仍走 MQ 消费者：
     * 那里有 CAS + 退券 + 回补秒杀名额，不能在这个任务里复制第二套。</p>
     */
    @Select("SELECT * FROM t_booking WHERE status = 'UNPAID' AND created_at < #{cutoff} " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<Booking> selectUnpaidBefore(@Param("cutoff") LocalDateTime cutoff,
                                     @Param("limit") int limit);

    /**
     * 累加一笔按张退款。
     *
     * <p>★SET 子句的顺序不能换：MySQL 从左到右求值，{@code status} 必须写在
     * {@code refund_amount = refund_amount + ...} 之前，这样 CASE 里读到的才是"加之前"的累计值。
     * 反过来的话第二次部分退款就会拿加过的新值再判一次，逻辑会多算一遍。</p>
     *
     * <p>★只有累计退款额达到实付额才把订单置 REFUNDED；没达到时订单仍是 PAID，
     * 表示"部分退款"。这样"买 2 张、用 1 张、另 1 张过期退一半"不会变成整单退款。</p>
     *
     * <p>★WHERE 带 status='PAID'：已全额退款/已取消的订单不能再累加退款。
     * 影响 0 行时说明订单已经是终态（旧逻辑整单退过/已取消），
     * 调用方只把凭证置过期，不再累加退款，避免任务每小时回滚重试。</p>
     *
     * @return 1 订单退款额已累加；0 订单不是 PAID（状态不允许再退）
     */
    @Update("UPDATE t_booking SET " +
            "status = CASE WHEN COALESCE(refund_amount, 0) + #{amount} >= pay_amount " +
            "              THEN 'REFUNDED' ELSE status END, " +
            "refund_amount = COALESCE(refund_amount, 0) + #{amount}, " +
            "refunded = 1, refund_at = #{now} " +
            "WHERE order_no = #{orderNo} AND status = 'PAID'")
    int addRefundAmount(@Param("orderNo") String orderNo,
                        @Param("amount") BigDecimal amount,
                        @Param("now") LocalDateTime now);

    // =====================================================================
    // 看板统计（docs 4.12 之外的管理端只读聚合）
    // ★三条都是"一条 SQL 出一个数/一页榜"，不在 Java 里捞一批再 stream 求和：
    //   订单表会一直长，捞全表进内存求和是最先爆掉的那一处
    // =====================================================================

    /**
     * 某状态订单在 {@code [start, end)} 区间的实付合计。
     * <p>COALESCE 是必需的：一天没有成交时 SUM 返回 <b>NULL</b> 而不是 0，
     * 直接接进 BigDecimal 会让看板显示空白甚至炸一次。</p>
     */
    @Select("SELECT COALESCE(SUM(pay_amount), 0) FROM t_booking" +
            " WHERE status = #{status} AND created_at >= #{start} AND created_at < #{end}")
    BigDecimal sumPayAmount(@Param("status") String status,
                            @Param("start") LocalDateTime start,
                            @Param("end") LocalDateTime end);

    /**
     * 累计退款金额。
     * <p>★必须 SUM(refund_amount) 而不是 SUM(pay_amount)：
     * 部分退款的订单状态仍是 PAID，按整单实付汇总会把没退的部分也算进去。</p>
     */
    @Select("SELECT COALESCE(SUM(refund_amount), 0) FROM t_booking")
    BigDecimal sumRefundedAmount();

    /**
     * 近 N 天热销课 TopN。
     * <p>★课程名用的是订单表自己的 {@code course_name} 快照列，<b>不回查课程服务</b>：
     * 排行榜要的是"当时卖出去的那门课叫什么"，而且少一次跨服务调用。</p>
     */
    @Select("SELECT course_name AS name, SUM(quantity) AS sold FROM t_booking" +
            " WHERE status = 'PAID' AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)" +
            " GROUP BY course_name ORDER BY sold DESC LIMIT #{limit}")
    List<HotCourseRow> selectHotCourses(@Param("days") int days, @Param("limit") int limit);

    /**
     * 近 N 天每日成交趋势。
     * <p>★{@code amount} 只算 PAID、{@code order_count} 算全部状态：
     * 看板上"金额"和"单量"两条线的口径本来就不一样（未支付/已退款的单也是单），
     * 把两者都限定成 PAID 反而会让人误读成"今天没人下单"。</p>
     * <p>按 {@code DATE(created_at)} 分组而不是按小时：趋势图横轴是"天"，
     * 按小时分组要多传一个参数、前端也要多一层格式化，没有收益。</p>
     */
    @Select("SELECT DATE_FORMAT(MIN(created_at), '%m-%d') AS date," +
            "       COALESCE(SUM(CASE WHEN status = 'PAID' THEN pay_amount ELSE 0 END), 0) AS amount," +
            "       COUNT(*) AS order_count" +
            "  FROM t_booking" +
            " WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)" +
            " GROUP BY DATE(created_at)" +
            " ORDER BY MIN(created_at)")
    List<TrendRow> selectTrend(@Param("days") int days);

    /**
     * 近 N 天订单状态分布（看板环形图）。
     * <p>★四个状态一次 GROUP BY 全出，不在 Java 里按状态查四次：
     * 分布图要的就是"合计 100%"，分四次查之间来了新单，占比加起来就不是 100%。</p>
     */
    @Select("SELECT status, COUNT(*) AS count FROM t_booking" +
            " WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)" +
            " GROUP BY status")
    List<StatusCountRow> selectStatusDist(@Param("days") int days);
}
