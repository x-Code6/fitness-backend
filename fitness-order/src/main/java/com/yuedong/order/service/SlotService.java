package com.yuedong.order.service;

import com.yuedong.order.model.vo.slot.SlotPageVO;

/**
 * 可约时段查询（流程 B，<b>只读</b>）。
 *
 * <p>★这个域的复杂度分布是刻意的：<b>读简单、写严格</b>。
 * 查询全程只有三条 SQL（凭证、教练占用、门店与教练走两次 Feign），
 * 没有锁、没有 Redis、没有并发防护；冲突只在创建预约那一刻由
 * {@code uk_coach_start} 唯一索引防。做缓存反而会把"某教练刚被约走"这种
 * 秒级变化的状态显示错，得不偿失。</p>
 */
public interface SlotService {

    /**
     * 查"某天某店某课"的完整时段页数据。
     *
     * @param userId   当前用户（决定"我的凭证"和 SELF_USED 灰态）
     * @param courseId 课程
     * @param storeId  门店。★凭证认店，所以时段页也必须按店查
     * @param date     yyyy-MM-dd；为空按今天
     */
    SlotPageVO slots(Long userId, Long courseId, Long storeId, String date);
}
