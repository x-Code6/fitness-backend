package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 权益凭证实体，对应表 yd_order.t_entitlement —— <b>买到的"可以去上课的资格"</b>。
 *
 * <p>v2 把旧模型里"一张订单就是一节预约"拆成两条：{@link Booking} 只记钱，
 * 本表记权益。用户付完款拿到 N 张凭证（N = 购买份数，★多份不合并，见 D4），
 * 之后每次预约用掉一张凭证上的一次。</p>
 *
 * <p>★两个 NULL 语义是这张表最容易写错的地方，判断条件必须成对出现：</p>
 * <ul>
 *   <li>{@code totalTimes == null} ⇒ 不限次（UNLIMITED）。次数校验必须写成
 *       {@code total_times IS NULL OR used_times < total_times}，
 *       漏掉前半句会把不限次课当成 0 次卡，用户永远约不了；</li>
 *   <li>{@code validUntil == null} ⇒ 永不过期（FOREVER）。过期扫描 SQL 必须带
 *       {@code valid_until IS NOT NULL}，漏掉后半句会把常年课集体误判过期并退款。</li>
 * </ul>
 */
@Data
@TableName("t_entitlement")
public class Entitlement {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归谁（跨库 → yd_user）。所有查询都必须带上它，否则就成了"猜到 id 就能用别人的凭证" */
    @TableField("user_id")
    private Long userId;

    /** 出票订单（跨库关联用订单号字符串，沿用 t_user_coupon.order_no 的惯例） */
    @TableField("order_no")
    private String orderNo;

    /** 哪门课（跨库 → yd_course） */
    @TableField("course_id")
    private Long courseId;

    /** ★只能在购买门店约教练——物理位置约束，不是快照美化 */
    @TableField("store_id")
    private Long storeId;

    @TableField("course_name")
    private String courseName;

    @TableField("store_name")
    private String storeName;

    /** 快照：一节课占几个整点格子 */
    @TableField("lesson_hours")
    private Integer lessonHours;

    /** 快照：TIMES / UNLIMITED。管理员改了课程售卖形态不能追溯改变已售凭证 */
    @TableField("sale_type")
    private String saleType;

    /** TIMES = 这张凭证给几次；UNLIMITED = null（不限次）。★见类注释的两个 NULL 语义 */
    @TableField("total_times")
    private Integer totalTimes;

    /** 已消费次数。扣次时机 = 预约到点后由定时任务 +1，取消预约不扣 */
    @TableField("used_times")
    private Integer usedTimes;

    /**
     * 出票时按份数分摊到的<b>实付金额</b>。
     * <p>★为什么不在退款时用 {@code booking.pay_amount / quantity} 现算：
     * 订单可能改价、券可能被后台调整，而“这张凭证当初付了多少钱”是成交那一刻的事实。
     * 买 2 份实付 150 元时，两张凭证各记 75 元；退款就按这张凭证的 75 元退，
     * 不会出现“退原价 100、用户还赚了券钱”。</p>
     */
    @TableField("paid_amount")
    private BigDecimal paidAmount;

    /** 已退款金额。一张凭证最多退一次，{@code refundedAt != null} 表示已经退过 */
    @TableField("refund_amount")
    private BigDecimal refundAmount;

    /** 退款时间；null = 未退款 */
    @TableField("refunded_at")
    private LocalDateTime refundedAt;

    /** 使用截止；★null = 永不过期。由 t_course_store 的 valid_type 三选一算出 */
    @TableField("valid_until")
    private LocalDateTime validUntil;

    /** 凭证轴状态，取值见 {@link com.yuedong.order.constant.EntitlementStatus} */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 这张凭证能不能覆盖<b>给定的那一刻</b>（通常是"上课时刻"或"现在"）。
     *
     * <p>★三个条件缺一不可，而且两处都是 NULL 语义判断，这就是把规则收进一个方法的原因：
     * <ol>
     *   <li>状态 ACTIVE（用完/过期的都不行）；</li>
     *   <li>还有次数：{@code totalTimes == null}（不限次）或 {@code used + pending < total}。
     *       <b>漏掉 null 分支会把 UNLIMITED 课判成 0 次卡，用户永远约不了</b>；</li>
     *   <li>未到有效期：{@code validUntil == null}（永久）或晚于给定时刻。
     *       <b>漏掉 null 分支会把常年课判成已过期</b>。</li>
     * </ol>
     *
     * <p>★参数里的 {@code pendingCount} 是"已经约了、但还没去上"的节数，2026-10-20 补的。
     * 之前只有 {@code usedTimes} 一个数，而它的语义是<b>上过课</b>（下课才 +1，D5），
     * 于是"约完一节课"到"真去上完"这段时间里，凭证在系统眼里仍然是满额的：
     * 一张三次的凭证可以同时把下周约满。次数没被扣是<b>有意</b>的（取消不扣次、爽约才算消费），
     * 缺的是"占用"这个维度——所以这里把占用作为参数传进来算，而不是往表里再塞一个计数字段：
     * 计数字段要和取消、超时结账、退款、过期、人工修数据每一处同步，漏一次就永久对不上，
     * 而且事后无法从数据里推断出正确值；占用数由 {@code t_reservation} 里有几行 BOOKED 决定，
     * 是<b>事实本身</b>，永远自愈。</p>
     *
     * <p>★还有一条来自数据库的硬约束：同一张凭证<b>同时只能挂一节待上的课</b>
     * （唯一索引 {@code uk_pending_entitlement}）。所以这里 {@code pendingCount >= 1} 直接不可约，
     * 不限次课也一样——否则一个人能拿一张卡把教练档期包圆。
     * 方法与索引必须同向，只写一边会出现"接口放过去了、插入被数据库拒了"的 500。</p>
     *
     * <p>参数用"哪一刻"而不是固定 now，是因为两条链路问的是不同的时刻：
     * 列表页问"现在还能不能用"，创建预约问"<b>上课时</b>还有效吗"
     * （有效期最后一天约晚上的课，用 now 判会误放行）。
     * 规则在两个地方各自实现迟早会漂移，所以只留这一份。</p>
     */
    public boolean bookableAt(java.time.LocalDateTime moment, int pendingCount) {
        if (!com.yuedong.order.constant.EntitlementStatus.ACTIVE.equals(this.status)) {
            return false;
        }
        if (pendingCount >= 1) {
            return false;
        }
        int usedNow = this.usedTimes == null ? 0 : this.usedTimes;
        boolean hasTimes = this.totalTimes == null || usedNow + pendingCount < this.totalTimes;
        return hasTimes && validCovers(moment);
    }

    /**
     * 有效期是否覆盖"上课那一刻"。★只管时间，不判次数与状态——
     * 创建预约时问的是"这一节上完之前会不会先过期"，和"现在能不能约"是两件事，
     * 混在一个方法里迟早出现"为了判时间顺手把状态也判了"的重复校验。
     */
    public boolean validCovers(java.time.LocalDateTime moment) {
        return this.validUntil == null || this.validUntil.isAfter(moment);
    }

    /**
     * 显示用的剩余次数：总次数 - 已上课 - 已约未上。
     *
     * <p>★返回 -1 表示不限次（{@code total_times} 为 NULL）。用 -1 而不是 Integer 装箱，
     * 是为了调用方拼文案时只有一个分支要判；但前端拿到的一律是 null，
     * 由 {@code EntitlementVO.unlimited} 明确标出，不把 -1 泄露出去当"负数次"。</p>
     */
    public int remainTimes(int pendingCount) {
        if (this.totalTimes == null) {
            return -1;
        }
        int usedNow = this.usedTimes == null ? 0 : this.usedTimes;
        return Math.max(0, this.totalTimes - usedNow - pendingCount);
    }
}
