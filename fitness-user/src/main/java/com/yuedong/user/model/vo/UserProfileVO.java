package com.yuedong.user.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 健身档案视图。
 * <p>★除了原始字段，还多给两个"算出来的"：{@code bmi} 和 {@code targetRemain}。
 * 它们<b>不落库</b>，每次读的时候现算——派生值一旦存下来就会过期（改身高、换体重都让它变错）。
 * 这和订单里"凭证有效期"的做法是一条规则：能算出来的不要存。</p>
 */
@Data
@Builder
public class UserProfileVO {

    private String goal;
    /** 目标的中文，前端直接显示，不抄枚举映射表 */
    private String goalText;
    private String experience;
    private String experienceText;
    private BigDecimal heightCm;
    private BigDecimal targetWeight;
    private String injuries;
    private List<String> preferSlots;
    /** 该用户在跟踪的指标 code。★从没设过档案的人返回全集，而不是空数组——否则他进录入页会看到一张空表 */
    private List<String> trackMetrics;

    /** 由最近一次体重 + 身高现算。任一缺失就为 null，前端显示"--" */
    private BigDecimal bmi;
    private String bmiText;
    /** 距离目标还差多少 kg。已经达成时为 null（文案交给前端显示"已达成"） */
    private BigDecimal targetRemain;
    private String targetRemainText;
    /** 最近一次体重，来自 Mongo。放这里是为了让「我的」页一次请求拿到卡片所需的全部数据 */
    private BigDecimal latestWeight;
    private String latestRecordDate;
}
