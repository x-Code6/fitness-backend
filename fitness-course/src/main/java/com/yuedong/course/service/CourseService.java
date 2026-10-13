package com.yuedong.course.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.ScheduleDTO;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.course.model.vo.ScheduleVO;

import java.util.List;

/**
 * 课程业务接口。
 */
public interface CourseService {

    /** 课程列表：type 筛选 + 关键词搜索，附带进行中的秒杀信息 */
    List<CourseVO> list(String type, String keyword);

    /**
     * 课程详情（缓存三件套：空值缓存防穿透 + 逻辑过期防击穿 + 随机 TTL 防雪崩）。
     */
    CourseVO getDetail(Long id);

    /** 某课程的未来可约场次列表（按时间升序） */
    List<ScheduleVO> scheduleList(Long courseId);

    /** 内部接口：课程简况（订单服务下单时查） */
    CourseDTO getInnerDetail(Long id);

    /** 内部接口：场次简况（订单服务按场次预约时查） */
    ScheduleDTO getScheduleInner(Long scheduleId);

    /** 内部接口：原子占用场次名额；false = 本场已满 */
    boolean occupySchedule(Long scheduleId);

    /** 内部接口：原子释放场次名额 */
    boolean releaseSchedule(Long scheduleId);

    /** 内部接口：原子占用课程名额（无场次模式的兼容路径）；false = 已满 */
    boolean occupy(Long courseId);

    /** 内部接口：原子释放课程名额 */
    boolean release(Long courseId);

    // =====================================================================
    // 管理端（fitness-admin-server Feign 调用）
    // =====================================================================

    /** 管理端：课程分页（类型/关键词筛选） */
    PageDTO<CourseAdminDTO> adminPage(String type, String keyword, Integer page, Integer size);

    /** 管理端：创建/编辑课程（编辑时不改 booked） */
    void adminSave(CourseSaveDTO dto);

    /** 管理端：上架/下架 */
    void adminStatus(Long id, Integer status);

    /** 管理端：删除课程（演示为物理删除；生产建议逻辑删除） */
    void adminDelete(Long id);

    /** 管理端：某课程的全部场次（含已过期的历史场次） */
    List<ScheduleDTO> scheduleAdminList(Long courseId);

    /** 管理端：新增场次 */
    void scheduleAdminSave(Long courseId, String scheduleTime, Integer capacity);

    /** 管理端：删除场次 */
    void scheduleAdminDelete(Long scheduleId);
}
