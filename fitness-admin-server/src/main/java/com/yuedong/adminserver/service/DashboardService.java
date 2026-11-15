package com.yuedong.adminserver.service;

import com.yuedong.adminserver.model.vo.DashboardVO;

/**
 * 管理端数据看板业务接口。
 */
public interface DashboardService {

    /** 聚合订单、课程、会员三路统计（每路独立降级） */
    DashboardVO summary();
}
