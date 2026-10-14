package com.yuedong.order.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.order.api.dto.ReservationAdminDTO;
import com.yuedong.order.model.dto.ReservationCreateDTO;
import com.yuedong.order.model.entity.Reservation;
import com.yuedong.order.model.vo.ReservationVO;

import java.util.List;

/**
 * 预约业务接口（时段轴，流程 C/D）。
 *
 * <p>★这个服务是 v2 并发设计的落点：<b>创建预约 = 在同一个本地事务里写预约单 + 占格子</b>。
 * 占用表 {@code t_coach_busy} 与预约表同在 yd_order，所以冲突由唯一索引拦、
 * 回滚由事务做，<b>一行补偿代码都不需要</b> —— 对照旧链路 B：那边占名额是跨服务 Feign，
 * 于是必须手写三段"这步失败要回上一步"，而每一段都是 bug 的温床。</p>
 */
public interface ReservationService {

    /**
     * 创建预约（唯一"必须防"级别的写接口）。
     *
     * @return 预约视图，含核销码与取消截止时间
     */
    ReservationVO book(Long userId, ReservationCreateDTO dto);

    /**
     * 取消预约：<b>不扣次</b>，并释放它占的全部格子。
     * <p>规则"距开课 1 小时内不可取消"由 {@code cancel_deadline > NOW()} 在数据库里判，
     * Java 不算时间。</p>
     */
    void cancel(Long userId, String reservationNo);

    /** 我的预约列表；status 为空或 ALL 表示全部 */
    List<ReservationVO> mine(Long userId, String status);

    /** 预约详情（核销码、取消截止、门店地址） */
    ReservationVO detail(Long userId, String reservationNo);

    // =====================================================================
    // 管理端
    // =====================================================================

    /**
     * 到店核销：<b>只登记到店</b>，不改状态、不扣次（D5）。
     *
     * @param verifyCode 10 位核销码
     * @return 核销后的预约视图，工作台要立即显示"谁、哪节课"
     */
    ReservationVO verify(String verifyCode);

    // =====================================================================
    // 定时任务
    // =====================================================================

    /**
     * 到点结账：把 {@code end_time} 已过但仍 BOOKED 的预约置 FINISHED 并扣掉凭证一次。
     * <p>★已确认口径是"下课才算消费完"（不是开课就扣），所以 2 小时的课要 2 小时后扣。
     * 取消的单走 CANCELED 分支，永远不会进这个扫描条件 —— <b>"取消不扣次"这条规则是免费的</b>。</p>
     *
     * @return 本轮结账的预约张数
     */
    int settleDue();

    // =====================================================================
    // 管理端（分页 / 按码核销 / 删教练前置判断）
    // =====================================================================

    /**
     * 管理端：预约分页。
     *
     * @param status  BOOKED / FINISHED / CANCELED，null 或 ALL 表示全部
     * @param coachId 按教练筛
     * @param storeId 按门店筛
     * @param date    某一天（yyyy-MM-dd）。★按 {@code start_time} 落在当天判，
     *                用 [当天00:00, 次日00:00) 的<b>闭开区间</b>而不是 DATE(start_time)=? ——
     *                对列套函数会让索引失效，一天一约的表也会扫全表
     * @param keyword 预约单号 / 订单号 / 课程名 / 教练名 模糊
     */
    PageDTO<ReservationAdminDTO> adminPage(String status, Long coachId, Long storeId,
                                          String date, String keyword, Integer page, Integer size);

    /**
     * 管理端：按核销码登记到店。
     * <p>★内部直接复用 {@link #verify(String)} 而不是重写一遍：
     * "一码一次"靠的是 {@code verified_at IS NULL} 这个 CAS 条件，写两遍就有一遍会漏。
     * 复用之后多查一次实体换回"规则只有一份"，这笔账在任何量级上都划算。</p>
     */
    ReservationAdminDTO adminVerify(String verifyCode);

    /** 某教练是否还有未开始的预约（删教练前的前置判断，见 BookingClient 的注释） */
    boolean hasFutureByCoach(Long coachId);

    /** 实体 → 管理端 DTO（公开理由同 {@code EntitlementService#toAdminDTO}） */
    ReservationAdminDTO toAdminDTO(Reservation reservation);
}
