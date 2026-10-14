package com.yuedong.course.api.client;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CoachDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 教练内部契约（order 服务算可约时段时调用）。
 *
 * <p>★和 {@code CouponClient} 一样单独立一个客户端，而不是塞进 {@code CourseClient}：
 * 一个客户端对应一个业务边界，将来教练要加方法时不会牵动课程契约，
 * Feign 的日志与熔断配置也能按服务边界分别调。</p>
 */
@FeignClient(name = "fitness-course", contextId = "coachClient")
public interface CoachClient {

    /**
     * 查"这门课在这家店有哪些可约教练"，带<b>计算所需的原始字段</b>（工时与可约星期）。
     *
     * <p>只返回 {@code status=1} 的教练：停用的教练不进时段页，
     * 这一点在课程侧的 SQL 里已经过滤，调用方不需要再判。</p>
     *
     * @param courseId 课程 id
     * @param storeId  门店 id
     * @return 教练列表；没有匹配教练时返回<b>空集合</b>而不是 null
     */
    @GetMapping("/inner/coach/bookable")
    Result<List<CoachDTO>> bookable(@RequestParam("courseId") Long courseId,
                                    @RequestParam("storeId") Long storeId);
}
