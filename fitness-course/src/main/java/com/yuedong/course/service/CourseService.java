package com.yuedong.course.service;

import com.yuedong.course.api.dto.AdminCountDTO;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.CourseStoreKeyDTO;
import com.yuedong.course.api.dto.CourseWindowAdminDTO;
import com.yuedong.course.api.dto.CourseWindowSaveDTO;
import com.yuedong.course.model.entity.Course;
import com.yuedong.course.model.entity.SeckillActivity;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.common.model.PageDTO;

import java.util.List;

/**
 * 课程业务接口。
 *
 * <p>★v2 相对旧接口删掉的方法，以及删除理由（都不是"暂时没做"，是模型变了就不该存在）：</p>
 * <ul>
 *   <li>{@code scheduleList / getScheduleInner / occupySchedule / releaseSchedule}
 *       与 {@code occupy / release}：全部与"场次 + 座位"绑定。
 *       团课取消后，可约时刻由<b>教练时段</b>提供（order 域的 {@code t_coach_busy}），
 *       "有没有位子"变成"这个教练这个整点被占没被占"，课程侧不再持有名额；</li>
 *   <li>{@code scheduleAdminList / scheduleAdminSave / scheduleAdminDelete}：管理端场次维护，同上。</li>
 * </ul>
 *
 * <p>带来的连带收益：购买链路不再需要"跨服务占名额 + 失败补偿"。
 * 旧 {@code BookingServiceImpl.create} 里有三处手写补偿（落库失败释放、用券失败释放、超时消费释放），
 * 现在购买只写自己的订单表，一处都不用留。</p>
 */
public interface CourseService {

    /**
     * 课程列表。
     *
     * @param type    分类，空或"全部"表示不限
     * @param keyword 关键词，匹配课程名/教练名/标签（★旧实现把 name 条件叠加了两次，
     *                导致按教练或标签搜索永远搜不到，本次一并修）
     * @param kind    SALE / CONTENT，null 表示两类都要
     */
    List<CourseVO> list(String type, String keyword, String kind);

    /**
     * 课程详情（缓存三件套）。
     *
     * @param id      课程 id
     * @param storeId 可空的门店上下文：传了就只算这家店的"几位教练可约"，
     *                并把该店排在可售门店列表首位；没传则返回全部可售门店
     */
    CourseVO getDetail(Long id, Long storeId);

    /** 内部接口：给 order 服务建单时取课程快照字段 */
    CourseDTO getInnerDetail(Long courseId, Long storeId);

    /**
     * 批量取"课程 × 门店"快照（购物车列表/结算用，一次 RPC 拿全）。
     *
     * <p>★与 {@link #getInnerDetail} 的两点不同：
     * <b>不抛"课程不存在"</b>（购物车里出现下架的课是正常事，要显示"已下架"而不是打不开整页），
     * 且<b>下架的课也会返回</b>（{@code onSale=false}），由调用方决定怎么标灰。</p>
     */
    List<CourseDTO> getBatchDetail(List<CourseStoreKeyDTO> keys);

    /**
     * 组装 C 端课程 VO（<b>给本模块其他服务复用，避免同一套 VO 逻辑写两遍</b>）。
     *
     * <p>以前 {@code SeckillActivityServiceImpl.listActive()} 里把 CourseVO 的拼装
     * 手抄了一遍，于是 VO 一改字段就要在两处同步——这类"复制出来的组装逻辑"
     * 正是接口返回字段在不同页面表现不一致的常见来源。</p>
     *
     * <p>入参用实体是因为调用方手里已经有实体（批量查过），传 id 反而会引发重复查库。
     * 本方法只被同模块调用，不构成对外契约。</p>
     *
     * <p>★不填门店维度的 {@code coachCount}：调用方没有单一门店上下文
     * （秒杀页是全店混排），要填就得上一次列表查一次教练——那是 N+1。</p>
     */
    CourseVO buildCourseVO(Course course, SeckillActivity activity);

    // =====================================================================
    // 管理端（fitness-admin-server 经 Feign 调用）
    // =====================================================================

    /** 管理端：课程分页 */
    PageDTO<CourseAdminDTO> adminPage(String type, String kind, Integer status,
                                      String keyword, Integer page, Integer size);

    /** 管理端：新增/修改课程（★不含可售门店配置，那部分由 course-store 维护接口单独做） */
    void adminSave(CourseSaveDTO dto);

    /** 管理端：上下架（下架后详情缓存立即失效） */
    void adminStatus(Long id, Integer status);

    /** 管理端：删除课程 */
    void adminDelete(Long id);

    /** 管理端：某课程当前的可售门店 id 列表（表单回显） */
    List<Long> adminStoreIds(Long courseId);

    /**
     * 管理端：整体替换某课程的可售门店（先删后插，与教练配课同一套语义）。
     *
     * <p>★新增关系时用<b>默认时间窗</b>：开售=此刻、停售=长期、有效期=领后 30 天。
     * 因为 {@code sell_start_time} 是 NOT NULL，留空会让这条关系永远判为"不可售"，
     * 而这种"配了门店却没生效"的问题最难查。两层时间的精细配置由独立接口负责（本期未做，
     * 管理端表单也还没有这一格）。</p>
     *
     * @param storeIds 门店 id 列表；空集合表示下架全部门店（课相当于是不可购买状态）
     */
    void adminAssignStores(Long courseId, List<Long> storeIds);

    /**
     * 管理端：课程 × 门店时间窗分页（docs 里的"两层时间"那一格，本期补上）。
     *
     * <p>{@code assignStores} 只负责"这门课在哪家店卖"（建关系、给默认窗口），
     * 这里负责"在一家店怎么卖"（改窗口）。★拆成两个接口是因为它们的写形状不同：
     * 前者先删后插整组、后者只改一行；合成一个接口就会出现
     * "改个停售时间顺手把别的门店配置清了"的事故。</p>
     */
    PageDTO<CourseWindowAdminDTO> adminWindowPage(Long courseId, Long storeId, Integer page, Integer size);

    /** 管理端：保存某门课在某家店的售卖窗口与凭证有效期 */
    void adminWindowSave(CourseWindowSaveDTO dto);

    /**
     * 管理端看板：课程侧计数（在售课 / 可约教练 / 营业门店 / 今日应开放时段格子）。
     *
     * <p>★{@code todaySlotTotal} 的口径见 {@code AdminCountDTO} 注释：
     * 只算<b>今天上班</b>的可约教练，每人 {@code workEndHour - workStartHour} 个整点。
     * 这是"占用率"的分母，和 order 那边的分子（已占格子数）必须同单位。</p>
     */
    AdminCountDTO adminCount();
}
