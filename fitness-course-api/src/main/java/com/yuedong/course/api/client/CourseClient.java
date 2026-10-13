package com.yuedong.course.api.client;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.ScheduleDTO;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 课程服务业务契约（订单服务下单/取消时 Feign 调用）。
 *
 * <p>返回 Result&lt;T&gt; 由 OpenFeign 的 Jackson 解码；调用方需检查 code != 0 的情况
 * （课程服务把业务错误也包在 200 的信封里返回）。</p>
 */
@FeignClient(name = "fitness-course")
public interface CourseClient {

    /** 课程简况 */
    @GetMapping("/inner/course/detail")
    Result<CourseDTO> detail(@RequestParam("id") Long id);

    /** 场次简况（按场次预约时查上课时间） */
    @GetMapping("/inner/course/schedule")
    Result<ScheduleDTO> schedule(@RequestParam("id") Long scheduleId);

    /** 原子占用场次名额：data=false 表示本场已满 */
    @PostMapping("/inner/course/schedule/occupy")
    Result<Boolean> occupySchedule(@RequestParam("scheduleId") Long scheduleId);

    /** 原子释放场次名额 */
    @PostMapping("/inner/course/schedule/release")
    Result<Boolean> releaseSchedule(@RequestParam("scheduleId") Long scheduleId);

    /** 原子占用课程名额（无场次兼容路径）：data=false 表示已满 */
    @PostMapping("/inner/course/occupy")
    Result<Boolean> occupy(@RequestParam("courseId") Long courseId);

    /** 原子释放课程名额 */
    @PostMapping("/inner/course/release")
    Result<Boolean> release(@RequestParam("courseId") Long courseId);

    /** 查询课程进行中的秒杀活动：data=null 表示没有活动 */
    @GetMapping("/inner/course/seckill/active")
    Result<SeckillActiveDTO> activeSeckill(@RequestParam("courseId") Long courseId);

    /** 秒杀建单成功后扣减 DB 库存（Redis 已先行扣减，此处最终一致） */
    @PostMapping("/inner/course/seckill/deduct")
    Result<Boolean> deductRemain(@RequestParam("activityId") Long activityId);
}
