package com.yuedong.user.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 记一次身体数据的入参。
 *
 * <p>★<b>没有 min/max 注解</b>，范围校验全部走 {@code BodyMetric} 那份元数据。
 * 原因：写在注解上就等于"每个字段各存一份范围"，而前端还要一份才能做即时提示，
 * 两处一改就漂。现在前端调 {@code GET /body-record/metrics} 拿同一份定义，
 * 后端保存时按同一份定义拦，只有一处需要维护。</p>
 *
 * <p>★除体重外全部可空，而且"不填"必须是 null 而不是 0——
 * 0 会被画进趋势图，成为一个假的数据点。保存前服务层会把 null 字段整个从文档里去掉。</p>
 */
@Data
public class BodyRecordSaveDTO {

    /**
     * 记录日期 {@code yyyy-MM-dd}。不传=今天。
     * <p>允许补录过去某天（"昨天忘了记"是真实需求），但服务层会拒绝未来的日期。</p>
     */
    private String recordDate;

    /** 体重 kg。★必填 */
    private BigDecimal weight;

    /** 体脂率 % */
    private BigDecimal bodyFatRate;

    /** 腰围 cm */
    private BigDecimal waistCm;

    /** 胸围 cm */
    private BigDecimal chestCm;

    /** 臀围 cm */
    private BigDecimal hipCm;

    /** 睡眠小时 */
    private BigDecimal sleepHours;

    /** 精力 1~5 */
    private Integer energyLevel;

    /** 酸痛 0~3 */
    private Integer sorenessLevel;

    /** 备注，≤40 字（服务层判长度，不用注解是因为超了要给用户看具体上限） */
    private String note;
}
