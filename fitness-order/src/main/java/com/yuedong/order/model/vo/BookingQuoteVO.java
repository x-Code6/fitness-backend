package com.yuedong.order.model.vo;

import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CouponUsableDTO;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次算价的结果。
 *
 * <p>★为什么不返回 BigDecimal 而是包一层：下单页要显示的东西是一组相关的数
 * （原价、抵扣、实付、券名、券不可用的原因、这次会出几张凭证），
 * 拆成多个方法各查一遍，就会出现"两次查到的课程价格不一样"。一次算完一起给，
 * 页面上那几个数字天然同源。</p>
 */
@Data
@Builder
public class BookingQuoteVO {

    /** 课程快照（名称、门店、课时、售卖形态、每次给几次都在里面） */
    private CourseDTO course;

    /** 归一化后的份数：null 或小于 1 按 1，超过上限直接报错（不是静默截断） */
    private int quantity;

    /** 单价快照：来自课程，★永远不接受前端传价 */
    private BigDecimal unitPrice;

    /** 原价小计 = 单价 × 份数 */
    private BigDecimal originalAmount;

    /** 券抵扣额。券不可用时为 0（不是"按这张券算但先不收"） */
    private BigDecimal discountAmount;

    /** 实付 = 原价 - 抵扣，最低 0 */
    private BigDecimal payAmount;

    /**
     * 一单给几次（TIMES 形态）；UNLIMITED 为 null 表示不限次。
     * <p>★这条规则（含"timesPerBuy 为空或小于 1 就按 1 兜底"）也放在这里算，
     * 因为建单要落进 t_booking.times_granted、试算页要拿它拼"每张可上 2 次"的文案。
     * 两处各写一遍的话，页面上写的"2 次"和实际出票的"1 次"能差出一个客诉。</p>
     */
    private Integer timesGranted;

    /** 用户选的券记录 id；没用券为 null */
    private Long couponRecordId;

    /** 选的券此刻能不能用 */
    private boolean couponUsable;

    /** 券名快照（建单时要落进 t_booking.coupon_title） */
    private String couponTitle;

    /** 券不可用的原因（人话，直接给前端显示："还差 50 元可用"） */
    private String couponReason;

    /** 这个金额下用户所有未使用的券的判定结果，只有 withCouponList=true 时才填 */
    private List<CouponUsableDTO> couponOptions;

    /** 秒杀活动 id。★非 null 就是秒杀单，会落进 t_booking.activity_id，
     *  也是管理端统计"这场活动卖了多少"的唯一依据（普通单永远是 null） */
    private Long activityId;

    /** 秒杀标记。份数锁 1、不给用券，前端据此隐藏这两个控件，而不是"提交了才报错" */
    private boolean seckill;

    /** 活动结束时间（毫秒）。确认页倒计时用——秒杀页没有倒计时等于没做完 */
    private Long seckillEndTime;
}
