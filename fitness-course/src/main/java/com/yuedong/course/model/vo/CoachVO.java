package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * C 端教练展示对象（可约教练列表 / 时段页表头）。
 *
 * <p>★刻意不包含的字段，以及原因（VO 只给出前端要展示的，不是把实体全字段透出去）：</p>
 * <ul>
 *   <li>{@code phone}：教练手机号属于个人信息，前台暴露即合规问题；</li>
 *   <li>{@code storeId/workDays}：时段页一次只在一个门店内查询，门店 id 由页面上下文已知；
 *       可约星期已经参与后端计算，前端不需要再算一遍；</li>
 *   <li>{@code status/createdAt}：状态与审计字段，展示层用不到。</li>
 * </ul>
 *
 * <p>★{@code freeTodayCount} 与 {@code nextFreeAt} 这两列<b>现在填不出来</b>：
 * 教练占用数据在 {@code yd_order.t_coach_busy}（跨库）。
 * 等 order 域的时段接口做完，这两列由那边一次批量查出后填充；
 * 在那之前它们为 null，前端要按"暂无时段数据"渲染而不是显示 0——
 * <b>0 和"还没算"是两件事，不要用 0 表示未知</b>。</p>
 */
@Data
@Builder
public class CoachVO {

    /** 教练 id，创建预约时回传 */
    private Long coachId;

    /** 姓名 */
    private String name;

    /** 资质文案，如 NSCA-CPT */
    private String title;

    /** 简介 */
    private String intro;

    /** 照片相对路径；为 null 时前端用 avatarColor 画纯色头像 */
    private String avatarImage;

    /** 头像配色下标 0~5 */
    private String avatarColor;

    /** 每日可约起始整点，前端画时间轴左边界用（与门店营业时间取交集后的结果） */
    private Integer workStartHour;

    /** 每日可约结束整点，前端画时间轴右边界用 */
    private Integer workEndHour;

    /** 今日剩余可约时段数（待 order 域接入，当前为 null） */
    private Integer freeTodayCount;

    /** 最近一个可约整点，格式化字符串如 "18:00"（待 order 域接入，当前为 null） */
    private String nextFreeAt;
}
