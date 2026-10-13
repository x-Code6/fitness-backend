package com.yuedong.course.service;

import com.yuedong.course.api.dto.SeckillAdminDTO;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import com.yuedong.course.api.dto.SeckillSaveDTO;
import com.yuedong.course.model.vo.CourseVO;

import java.util.List;

/**
 * 秒杀活动业务接口。
 */
public interface SeckillActivityService {

    /** 进行中的秒杀课程列表（含秒杀价/库存/倒计时截止） */
    List<CourseVO> listActive();

    /** 内部接口：查询课程进行中的活动；无则返回 null（endTime 供订单侧算资格集合 TTL） */
    SeckillActiveDTO activeSeckill(Long courseId);

    /** 内部接口：秒杀建单成功后扣减 DB 库存（Redis 是第一现场，DB 最终一致） */
    boolean deductRemain(Long activityId);

    /** 内部接口：活动开始前把 DB 库存预热进 Redis（Lua 扣减的初始值） */
    boolean preheat(Long activityId);

    // =====================================================================
    // 管理端（fitness-admin-server Feign 调用）
    // =====================================================================

    /** 管理端：全部活动列表（含课程名） */
    List<SeckillAdminDTO> adminList();

    /** 管理端：创建活动（status=待开始，由定时任务到点预热并置为进行中） */
    void adminSave(SeckillSaveDTO dto);

    /** 管理端：提前结束活动 */
    void adminEnd(Long activityId);
}
