package com.yuedong.order.service;

import com.yuedong.order.api.dto.AdminStatDTO;

/**
 * 管理端看板的订单侧统计。
 *
 * <p>★单独一个服务而不是塞进 {@code BookingService}：看板是<b>只读聚合</b>，
 * 里面每一条都是"口径问题"（什么算成交、退款怎么算、占用率的分子分母），
 * 和下单/支付/取消那套写逻辑没有关系。混在一起的话，
 * 改一个看板数字要动到交易核心类，风险完全不成比例。</p>
 */
public interface AdminStatService {

    /** 一次算完看板需要的订单侧数字（无参数：周期都是定死的"今天/近 7 天"） */
    AdminStatDTO stat();
}
