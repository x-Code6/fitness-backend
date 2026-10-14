package com.yuedong.order.model.vo.slot;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 时段页整页数据（<b>预约页唯一的一个读接口</b>）。
 *
 * <p>★结构上的一个决定：{@code my}（我的凭证）放在<b>页面级而不是每个教练里</b>。
 * 剩余次数对同一时刻的所有教练都相同，塞进 10 个教练对象里就是 10 份重复数据
 * 加 10 份潜在不一致（某份没更新到）。字段归属原则：<b>页面级信息挂页面，条目级信息挂条目</b>。</p>
 *
 * <p>接口放在 order 域（{@code /api/reservation/slots}）而不是 course 域：
 * 占用表 {@code t_coach_busy} 在 yd_order，放课程侧反而要跨库取数。
 * <b>接口位置由数据归属决定，不由"看起来像哪个功能"决定。</b></p>
 */
@Data
@Builder
public class SlotPageVO {

    /** 查询日期 yyyy-MM-dd，原样回给前端做标题 */
    private String date;

    /** 日期友好文案："今天""明天""周六"（前端自己算星期容易和后端时区不一致） */
    private String dateLabel;

    private Long storeId;

    private String storeName;

    /** ★凭证认店：页面要显示"只能在某某店约"，这行字是预约失败的最大来源之一 */
    private String courseName;

    /** 本课程一节几小时（决定格子分组） */
    private Integer lessonHours;

    /** 门店营业时间文案 "09:00 - 21:00"，前端画时间轴边界用 */
    private String openText;

    /**
     * 规则说明条目（整块弹窗渲染）。
     * <p>★由课程域的规则策略生成，切片C 接进来；这里先留字段，
     * 免得接口先发出去、前端接了再改一次契约。</p>
     */
    private List<String> ruleTexts;

    /** 页面级：我的凭证状态。★不进 coaches[]，理由见类注释 */
    private SlotMyVO my;

    /** 该课在本店的可约教练 + 每人的格子表 */
    private List<SlotCoachVO> coaches;
}
