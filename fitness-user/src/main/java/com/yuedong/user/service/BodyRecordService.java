package com.yuedong.user.service;

import com.yuedong.user.model.dto.BodyRecordSaveDTO;
import com.yuedong.user.model.vo.BodyViews;

import java.util.List;

/**
 * 身体记录业务接口（存 MongoDB）。
 *
 * <p>★数据归属：这条数据是"关于这个人"的，所以和 {@code t_user} 在同一个服务里。
 * 放进 order 或 behavior 都会导致"要取用户的东西得跨服务 RPC"。</p>
 */
public interface BodyRecordService {

    /** 指标元数据全集，前端渲染表单与趋势切换器的唯一依据 */
    List<BodyViews.MetricOption> metrics();

    /**
     * 记一次。<b>一天一条，同一天重复提交就是更新</b>（幂等键是 {@code userId + recordDate}）。
     *
     * @return 保存后的当天记录，前端直接刷新显示，不必再查一次
     */
    BodyViews.Record save(Long userId, BodyRecordSaveDTO dto);

    /** 最近一条 + 与上一条的体重差。「我的」页入口卡片用 */
    BodyViews.Record latest(Long userId);

    /** 历史列表，新→旧。★含 weightChange，列表里"▼0.3 比上次"由后端算好 */
    List<BodyViews.Record> list(Long userId, int days);

    /** 删掉某一天的记录。★找不到不报错，返回是否真删掉（前端按结果刷新） */
    boolean delete(Long userId, String recordDate);

    /**
     * 某个指标的趋势数据包。
     *
     * @param metric 指标 code，必须来自 {@link #metrics()}，传错直接报参数错误
     * @param days   回溯天数（30/90/0=全部）
     */
    BodyViews.Trend trend(Long userId, String metric, int days);
}
