package com.yuedong.course.service;

import com.yuedong.course.model.dto.CoachSaveDTO;
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
}
