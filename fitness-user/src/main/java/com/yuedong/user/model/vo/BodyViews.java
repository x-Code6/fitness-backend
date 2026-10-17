package com.yuedong.user.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 身体数据的视图对象集合（一个文件里放四个小类，因为它们只服务于"身体数据"这一个页面族，
 * 拆成四个文件反而要来回翻）。
 */
public class BodyViews {

    /** 一条记录（列表页与"最近一次"卡片都用它） */
    @Data
    @Builder
    public static class Record {
        /** 记录日期 yyyy-MM-dd。★不对外暴露 Mongo 的 _id：它没有业务含义，多给一个标识就多一条攻击面 */
        private String recordDate;
        /** 星期几，中文。列表页"10-17 周五"这种显示靠它，不让前端自己算（算错周几很常见） */
        private String weekdayText;
        private BigDecimal weight;
        private BigDecimal bodyFatRate;
        private BigDecimal waistCm;
        private BigDecimal chestCm;
        private BigDecimal hipCm;
        private BigDecimal sleepHours;
        private Integer energyLevel;
        private Integer sorenessLevel;
        private String note;
        /** 精力文案（"一般"/"很好"），★数字转人话由后端做，前端不抄映射表 */
        private String energyText;
        /** 酸痛文案（"不酸"/"中度酸痛"） */
        private String sorenessText;
        /** 更新时间文案，如"今天 21:30" */
        private String updatedText;
        /** 与上一条记录的体重差，正数=涨了。列表里那个 ▼0.3 由后端算，两处口径不会不一致 */
        private BigDecimal weightChange;
        private String weightChangeText;
    }

    /** 趋势图数据包 */
    @Data
    @Builder
    public static class Trend {
        private String metric;
        private String metricLabel;
        private String unit;
        /** 小数位，前端决定 tooltip 保留几位 */
        private int decimals;
        private int days;
        /** 时间升序的点。★缺失的日期<b>不会</b>补 0——没记录就没有点，图上自然断开 */
        private List<Point> points;
        /** 最新值 */
        private BigDecimal latest;
        /** 区间内相对首个点的变化（减脂场景是负数才好） */
        private BigDecimal change;
        private String changeText;
        /** 变化方向的语义：DOWN 绿（减脂是好事）/ UP 红 / FLAT 灰。★由后端给，别在前端用"数值变小就上绿色"这种硬编码 */
        private String changeDirection;
        private BigDecimal maxValue;
        private BigDecimal minValue;
        private BigDecimal avgValue;
        /** 样本数。★< 2 时前端不画折线，只显示一个点（连出去就是假信息） */
        private int sampleCount;
        /** 目标值（来自 MySQL 档案），画参考虚线；没有则为 null */
        private BigDecimal target;
        private String targetText;
        /** 空态文案，后端直接给："还没有记录过体脂，去记一次" */
        private String emptyText;
    }

    /** 趋势图上的一个点 */
    @Data
    @Builder
    public static class Point {
        private String recordDate;
        private BigDecimal value;
    }

    /** 指标元数据下发（前端渲染录入表单和趋势切换器的唯一依据） */
    @Data
    @Builder
    public static class MetricOption {
        private String code;
        private String label;
        private String unit;
        private int decimals;
        private BigDecimal min;
        private BigDecimal max;
        private boolean required;
        /** 录入页要不要折叠在"更多围度"里：胸围/臀围=true。★分组信息也在这份元数据里，前端不另建一套 */
        private boolean advanced;
    }
}
