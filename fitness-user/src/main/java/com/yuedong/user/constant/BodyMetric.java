package com.yuedong.user.constant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 身体指标元数据（★整个功能唯一的"指标定义处"）。
 *
 * <p>录入页要渲染哪些输入框、每个框的范围和小数位、趋势页切换器上有哪几项、
 * 校验该拦在哪——这四件事以前各写一遍就一定会漂（前端说体脂最大 50、后端校验 60
 * 是这类功能最常见的事故）。所以这里定一份，通过
 * {@code GET /api/body-record/metrics} 下发给前端，前端只渲染、不再抄一份常量。</p>
 *
 * <p>★和 {@code t_user_profile.track_metrics} 的关系：这里是<b>全集</b>，
 * 那份是<b>这个人勾选要跟踪的子集</b>。想让用户自定义字段？不做的理由见档案表注释——
 * 让他从全集里选，比让他发明字段便宜一个数量级，而且统计不会散架。</p>
 */
public enum BodyMetric {

    /** 体重。唯一必填项——没有它的记录不知道该拿来做什么 */
    WEIGHT("weight", "体重", "kg", 1, new BigDecimal("20"), new BigDecimal("300"), true),

    /** 体脂率。家用体脂秤读数手抄，属于"手动录入"，不是实时设备 */
    BODY_FAT("bodyFatRate", "体脂率", "%", 1, new BigDecimal("3"), new BigDecimal("60"), false),

    /** 腰围。皮尺，减脂期比体重更诚实 */
    WAIST("waistCm", "腰围", "cm", 1, new BigDecimal("30"), new BigDecimal("200"), false),

    /** 胸围。进阶围度，前端折叠 */
    CHEST("chestCm", "胸围", "cm", 1, new BigDecimal("40"), new BigDecimal("200"), false),

    /** 臀围。同上 */
    HIP("hipCm", "臀围", "cm", 1, new BigDecimal("40"), new BigDecimal("200"), false),

    /** 睡眠时长。0.5 小时步进，它是"体重莫名上涨"的头号解释 */
    SLEEP("sleepHours", "睡眠", "h", 1, new BigDecimal("0"), new BigDecimal("24"), false),

    /** 精力自评 1~5，点一下就能填 */
    ENERGY("energyLevel", "精力", "分", 0, new BigDecimal("1"), new BigDecimal("5"), false),

    /** 酸痛 0~3。★0 是合法值"不酸"，所以它用整型枚举而不是靠字段缺失表达 */
    SORENESS("sorenessLevel", "酸痛", "级", 0, new BigDecimal("0"), new BigDecimal("3"), false);

    /** 对外标识，前端传这个值选指标；★它同时也是 Mongo 文档里的字段名，省掉一层映射表 */
    private final String code;

    private final String label;

    private final String unit;

    /** 展示小数位：0 表示整数指标（精力/酸痛），前端用它决定输入框用 number 还是 stepper */
    private final int decimals;

    /**
     * 合理范围。★两端都做校验的理由很具体：手机上小数点漏输会把 72.5 录成 725，
     * 一个 725 的点能把整条折线压成一条直线，用户只会以为功能坏了。
     * 范围不是"医学正确值"，是"明显不可能是真话"的拦截线。
     */
    private final BigDecimal min;

    private final BigDecimal max;

    /** 是否必填（全集里只有体重） */
    private final boolean required;

    BodyMetric(String code, String label, String unit, int decimals,
               BigDecimal min, BigDecimal max, boolean required) {
        this.code = code;
        this.label = label;
        this.unit = unit;
        this.decimals = decimals;
        this.min = min;
        this.max = max;
        this.required = required;
    }

    /**
     * 按 code 找指标。
     * <p>★找不到就返回 null 而不是抛异常：趋势接口是用户传参进来的，
     *   传错指标名是一个"正常的错误输入"，由调用方决定给什么文案，
     *   别让枚举替接口决定错误码。</p>
     */
    public static BodyMetric of(String code) {
        for (BodyMetric metric : values()) {
            if (metric.code.equals(code)) {
                return metric;
            }
        }
        return null;
    }

    /** 全部指标的 code，档案页的"跟踪项"默认全选时用它 */
    public static List<String> allCodes() {
        List<String> codes = new ArrayList<>();
        for (BodyMetric metric : values()) {
            codes.add(metric.code);
        }
        return codes;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getUnit() {
        return unit;
    }

    public int getDecimals() {
        return decimals;
    }

    public BigDecimal getMin() {
        return min;
    }

    public BigDecimal getMax() {
        return max;
    }

    public boolean isRequired() {
        return required;
    }
}
