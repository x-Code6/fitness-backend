package com.yuedong.order.service;

import com.yuedong.order.model.vo.MineSummaryVO;

/**
 * "我的"页面聚合接口（第 9 条需求的四宫格）。
 *
 * <p>★四条计数一次给全，不做四个接口：四宫格并排显示，
 * 四次请求必然出现数字互相跳动。四条都是走索引的 COUNT，量级完全不成问题。</p>
 */
public interface MineService {

    /** 四宫格计数 */
    MineSummaryVO summary(Long userId);
}
