package com.yuedong.order.api.client;

import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.RatingStatsDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 评分统计契约（course 服务取门店/课程的分数）。
 *
 * <p>★<b>为什么评价数据在 order、统计却要给 course 用</b>：
 * 写评价要校验"这单已支付、凭证已用完"，数据都在 yd_order，
 * 汇总统计同库才能和明细一个事务更新（不然就得引分布式事务）。
 * 但"门店卡片显示 4.8 分"是课程域的展示需求 ——
 * 所以这里提供<b>只读的批量接口</b>，写留在订单域。</p>
 */
@FeignClient(name = "fitness-order", contextId = "ratingClient")
public interface RatingClient {

    /**
     * 批量取评分统计。
     *
     * @param type STORE / COURSE / COACH
     * @param ids  目标 id 列表。★一次查完，调用方不要在循环里逐个取
     * @return 有评价的目标才出现在结果里；<b>一条评价都没有的 id 不会返回</b>，
     *         调用方按"缺失即暂无评分"处理（不要用 0 分表示"没人评过"）
     */
    @GetMapping("/inner/rating/stats")
    Result<List<RatingStatsDTO>> stats(@RequestParam("type") String type,
                                       @RequestParam("ids") List<Long> ids);
}
