package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * "我的"页面四宫格（对应前端 {@code GET /mine/summary}）。
 *
 * <p>★四个数字<b>一次给全，不做四个接口</b>：四宫格并排显示，
 * 四次请求必然出现数字互相跳动（A 刷新完 B 还没回，用户以为系统算错了）。
 * 代价是这个接口要跨三条轴数四次，但四条 COUNT 全在 yd_order 一个库里走索引，
 * 而且是"进我的页面才调一次"的低频接口——<b>用一次稍重的查询换一致的观感，是划算的。</b></p>
 */
@Data
@Builder
public class MineSummaryVO {

    /** 待支付（订单轴） */
    private Integer unpaidCount;

    /** 待使用（凭证轴：ACTIVE 且还有次数且未过期） */
    private Integer waitUseCount;

    /** 已预约（预约轴：BOOKED 且还没下课） */
    private Integer bookedCount;

    /** 待评价（订单已付、凭证全结束、且这条单没评过） */
    private Integer ratingCount;
}
