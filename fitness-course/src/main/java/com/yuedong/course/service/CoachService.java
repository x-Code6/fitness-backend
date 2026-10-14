package com.yuedong.course.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.course.api.dto.CoachAdminDTO;
import com.yuedong.course.api.dto.CoachSaveDTO;
import com.yuedong.course.model.entity.Coach;
import com.yuedong.course.model.vo.CoachVO;

import java.util.List;

/**
 * 教练业务接口。
 *
 * <p>职责边界：教练的<b>身份与会教范围</b>归本服务（课程域），
 * 而<b>谁占了哪个时段</b>归 order 服务（{@code yd_order.t_coach_busy}）。
 * 所以这里没有任何"某教练某时段是否空闲"的方法——那是预约域的事，
 * 别把两个库的数据混在一个 Service 里。</p>
 */
public interface CoachService {

    /**
     * 查"这门课在这家店有哪些可约教练"。
     *
     * @param courseId 课程 id
     * @param storeId  门店 id
     * @return 教练展示列表（已排除停用教练），按 id 升序保证前端顺序稳定
     */
    List<CoachVO> listByCourseAndStore(Long courseId, Long storeId);

    /**
     * 内部接口用：同上，但返回<b>实体</b>而不是展示 VO。
     *
     * <p>★为什么不复用 {@link #listByCourseAndStore}：C 端 VO 刻意不带
     * {@code workDays}（星期已参与后端计算，前端不该再算一遍），
     * 而 order 服务算可约时段必须按星期判断教练当天上不上班。
     * <b>展示对象一旦当跨服务契约用，就会为了加字段把展示层搞脏</b>
     * ——这和 {@link #listByStore} 返回实体的理由是同一条。</p>
     */
    List<Coach> listBookable(Long courseId, Long storeId);

    /**
     * 新增或修改教练。
     *
     * @param dto 入参，{@code id} 为空即新增
     * @return 教练 id
     */
    Long save(CoachSaveDTO dto);

    /**
     * 整体替换某教练的可授课范围（先删后插）。
     *
     * @param coachId   教练 id
     * @param courseIds 课程 id 列表，传空集合表示清空全部配课
     */
    void assignCourses(Long coachId, List<Long> courseIds);

    /** 查某教练当前已配的课程 id 列表，供管理端表单回显 */
    List<Long> courseIdsOf(Long coachId);

    /**
     * 管理端：查某门店的全部教练（含停用与联系电话）。
     *
     * <p>返回实体而不是 VO 是有意的：管理端可信，需要看到 phone/status；
     * C 端必须走 {@link #listByCourseAndStore} 拿 CoachVO。
     * <b>两个端口共用一个 VO 是常见事故源</b>——为了给管理端加字段，
     * 顺手把敏感字段也带给了 C 端。</p>
     *
     * <p>不做分页：一家店教练几十位，量级不值得分页；真要加再说。</p>
     */
    List<Coach> listByStore(Long storeId);

    /**
     * 管理端：教练分页（门店/状态/性别/关键词）。
     *
     * <p>★一页数据要拼三处：t_coach 本身、t_store 的名字、t_coach_course 的配课情况。
     * 后两者都<b>按本页的 coachId 一次批量查</b>，不在循环里逐条查——
     * 一页 10 条就是 10 次查询那种写法，翻页一多就是几十次空转。</p>
     */
    PageDTO<CoachAdminDTO> adminPage(
            Long storeId, Integer status, Integer gender, String keyword, Integer page, Integer size);

    /** 管理端：启用/停用教练。停用不影响历史预约（预约单上存的是教练名快照） */
    void adminStatus(Long id, Integer status);

    /**
     * 管理端：删除教练（连配课关系一起删，同一本地事务）。
     *
     * <p>★"该教练是否还有未完成的预约"<b>不在这里判</b>：那张表在 yd_order，跨库查不到，
     * 而且为了这个判断让 course 反向依赖 order，两个服务就缠死了。
     * 由 admin-server 先问 order、通过后再调本方法（BFF 编排）。
     * 删掉之后历史预约仍然显示教练名，因为 {@code t_reservation.coach_name} 是快照。</p>
     */
    void adminDelete(Long id);
}
