package com.yuedong.user.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.user.model.dto.BodyRecordSaveDTO;
import com.yuedong.user.model.vo.BodyViews;
import com.yuedong.user.service.BodyRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 身体数据接口（需登录，网关 {@code /api/body/**} → 本服务 {@code /body/**}）。
 *
 * <p>★所有查询都以 {@code UserContext.getUserId()} 为准，
 * 请求体里<b>没有 userId 字段</b>——一给前端开这个口子，"查别人的身体数据"就只是改个参数的事。</p>
 *
 * <p>★这里<b>不加 {@code @Idempotent}</b>：保存本身就是按 {@code (userId, 日期)} 覆盖，
 * 重复提交天然幂等，再加一层时间窗口只会把"用户发现填错了改一下"也挡掉。
 * <b>幂等靠数据模型保证，比靠时间窗口保证更可靠</b>——这条和下单用一次性令牌是同一个思路的两种做法。</p>
 */
@RestController
@RequestMapping("/body")
@RequiredArgsConstructor
public class BodyRecordController {

    private final BodyRecordService bodyRecordService;

    /**
     * 指标元数据全集（编码、名称、单位、范围、小数位、是否进阶、是否必填）。
     * <p>★前端渲染录入表单和趋势切换器都读它。这是"范围只有一份定义"的关键：
     * 后端改了范围，前端不用跟着改代码。</p>
     * <p>★它虽然不含用户数据，但<b>照样要登录</b>——身体数据整族都在网关鉴权之内，
     * 为一个接口开白名单不值得冒"整族被顺手放开"的风险。</p>
     */
    @GetMapping("/metrics")
    public Result<List<BodyViews.MetricOption>> metrics() {
        return Result.ok(bodyRecordService.metrics());
    }

    /** 记一次。★同一天重复提交=更新当天那条，不会多出记录 */
    @PostMapping("/record")
    public Result<BodyViews.Record> save(@Valid @RequestBody BodyRecordSaveDTO dto) {
        return Result.ok(bodyRecordService.save(UserContext.getUserId(), dto));
    }

    /** 最近一次。「我的」页入口卡片用；没有任何记录时 data 为 null，前端显示引导文案 */
    @GetMapping("/record/latest")
    public Result<BodyViews.Record> latest() {
        return Result.ok(bodyRecordService.latest(UserContext.getUserId()));
    }

    /**
     * 历史列表。
     *
     * @param days 回溯天数，0 = 全部。★上限在服务层不做，因为"看全部历史"是真实需求
     */
    @GetMapping("/record/list")
    public Result<List<BodyViews.Record>> list(@RequestParam(defaultValue = "90") int days) {
        return Result.ok(bodyRecordService.list(UserContext.getUserId(), days));
    }

    /** 删掉某一天的记录 */
    @PostMapping("/record/delete")
    public Result<Boolean> delete(@RequestParam String recordDate) {
        return Result.ok(bodyRecordService.delete(UserContext.getUserId(), recordDate));
    }

    /**
     * 趋势数据包（点、摘要、目标线、空态文案）。
     *
     * @param metric 指标 code，必须来自 {@link #metrics()}
     * @param days   30 / 90 / 0(全部)
     */
    @GetMapping("/trend")
    public Result<BodyViews.Trend> trend(@RequestParam String metric,
                                         @RequestParam(defaultValue = "30") int days) {
        return Result.ok(bodyRecordService.trend(UserContext.getUserId(), metric, days));
    }
}
