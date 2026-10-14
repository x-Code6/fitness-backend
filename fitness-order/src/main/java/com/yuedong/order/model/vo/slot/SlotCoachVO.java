package com.yuedong.order.model.vo.slot;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 时段页里一位教练的整块数据（表头 + 格子表）。
 *
 * <p>★{@code maxContinuousFree} 与 {@code nextFreeAt} 是给"点不到的格子"用的：
 * 用户想约 17:50，而 A 教练 18:00 才空出来——他点 17:00 那格看到 reason=BOOKED，
 * 前端把 {@code nextFreeAt:"18:00"} 显示成副标题"最早 18:00 可约"。
 * <b>用户不需要理解什么叫整点，但他一眼就知道该点哪。</b></p>
 */
@Data
@Builder
public class SlotCoachVO {

    private Long coachId;

    private String coachName;

    /** 资质文案，如 NSCA-CPT */
    private String coachTitle;

    /** 头像配色下标（照片字段这里不给：时段页只画小圆头像，颜色够用） */
    private String avatarColor;

    /** 该教练当天实际可约区间文案 = 门店营业时间 ∩ 教练工时（两人不同就是不同区间） */
    private String openText;

    /** 当天格子总数 */
    private Integer totalSlots;

    /** 物理空闲的格子数（不含"我没凭证"这层判断） */
    private Integer freeSlots;

    /**
     * 最长<b>连续</b>空闲小时数。
     * <p>★D3（一节课不可拆分）的直接产物：lessonHours=2 的课，
     * maxContinuousFree&lt;2 时这位教练当天根本约不了，
     * 前端可以据此把教练整块置灰并写"当天排不下完整一节"，
     * 而不是让用户一格一格试。</p>
     */
    private Integer maxContinuousFree;

    /** 第一个空闲整点，"18:00"；全天无空闲为 null */
    private String nextFreeAt;

    /** 格子表，按时间升序 */
    private List<SlotItemVO> slots;
}
