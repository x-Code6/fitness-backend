package com.yuedong.course.api.client;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseStoreKeyDTO;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 课程服务业务契约（order 服务下单/秒杀时经 Feign 调用）。
 *
 * <p>返回 {@code Result<T>}：OpenFeign 用 Jackson 解码，
 * 调用方必须检查 {@code code != 0} 的情况——课程服务把业务错误也包在 HTTP 200 的信封里返回。</p>
 *
 * <p>★v2 删掉的方法：{@code schedule}、{@code occupySchedule}、{@code releaseSchedule}、
 * {@code occupy}、{@code release}。它们全是"跨服务占座位"的产物：
 * 购买只写自己的订单表，不再占任何名额，
 * 于是 order 侧那三段失败补偿（落库失败释放 / 用券失败释放 / 超时释放）也一并删除。
 * <b>跨服务写操作少一处，能出问题的地方就少一处</b>——这正是"避免分布式事务"的落地方式：
 * 不是上 Seata，而是让这一步根本不需要跨服务写。</p>
 */
@FeignClient(name = "fitness-course", contextId = "courseClient")
public interface CourseClient {

    /**
     * 课程简况 + 该门店的可售状态。
     *
     * @param courseId 课程 id
     * @param storeId  购买门店 id。★必传：门店名快照与"是否在可售窗口内"都由
     *                 {@code t_course_store} 决定，缺了它 order 就无法判断这单能不能成
     */
    @GetMapping("/inner/course/detail")
    Result<CourseDTO> detail(@RequestParam("courseId") Long courseId,
                             @RequestParam("storeId") Long storeId);

    /**
     * 批量取"课程 × 门店"快照（购物车列表/结算用）。
     *
     * <p>★为什么专门要一个批量口子：购物车最多 20 条，逐条调 {@link #detail} 就是
     * 20 次网络往返——和在循环里查库是同一个毛病，而且更贵（每次都是一整套 HTTP）。
     * 服务端用三条 SQL（课程 IN、关系 IN、门店 IN）拼出结果，<b>一次 RPC 拿全</b>。</p>
     *
     * @param keys 要查的 (courseId, storeId) 对
     * @return 快照列表，顺序与入参一致；查不到的那一对<b>不会出现在结果里</b>
     *         （调用方按 courseId+storeId 回查，缺失即"该店已下架"）
     */
    @PostMapping("/inner/course/batch-detail")
    Result<List<CourseDTO>> batchDetail(@RequestBody List<CourseStoreKeyDTO> keys);

    /** 查课程进行中的秒杀活动：{@code data=null} 表示没有活动 */
    @GetMapping("/inner/course/seckill/active")
    Result<SeckillActiveDTO> activeSeckill(@RequestParam("courseId") Long courseId);

    /** 秒杀建单成功后扣减 DB 侧份数（Redis 已在 Lua 里先行扣减） */
    @PostMapping("/inner/course/seckill/deduct")
    Result<Boolean> deductRemain(@RequestParam("activityId") Long activityId);

    /**
     * 订单取消/超时/建单失败时回补 DB 侧份数。
     * <p>★Redis 和 DB 两个账本都要回补；只回补 Redis 会让 DB 名额永久少卖。</p>
     */
    @PostMapping("/inner/course/seckill/restore")
    Result<Boolean> restoreRemain(@RequestParam("activityId") Long activityId);
}
