package com.yuedong.user.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 健身档案（MySQL，一人一行）。
 *
 * <p>★为什么不和身体记录一起放 Mongo：这些字段<b>有界</b>（一人就一份，改了覆盖），
 * 而且管理端要按"目标=减脂"筛人、要统计会员构成，那是关系型的活。
 * 一个建模判断值得记住：<b>"字段可缺、按人取一段"才适合文档；
 * "一人一份、要跨人统计"就该留在表里。</b></p>
 */
@Data
@TableName("t_user_profile")
public class UserProfile {

    /** 主键就是用户 id（不是自增 id）。★一人一行的表用 user_id 当主键，天然唯一，省一个索引 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /** 目标：LOSE_FAT 减脂 / BUILD_MUSCLE 增肌 / KEEP_FIT 保持 / STRENGTH 力量。★枚举字符串，不用 1/2/3 */
    private String goal;

    /** 训练经验：NEWBIE 零基础 / UNDER_1Y 一年内 / OVER_1Y 一年以上 */
    private String experience;

    /** 身高 cm。★存它而不是存 BMI：BMI 是派生值，身高一变历史 BMI 全错 */
    @TableField("height_cm")
    private BigDecimal heightCm;

    /** 目标体重 kg。趋势页那条参考虚线读它。★不写进每条身体记录，否则改目标要刷全部历史 */
    @TableField("target_weight")
    private BigDecimal targetWeight;

    /** 伤病史，教练排动作要看。纯文本，不做结构化（结构化会诱导你去建一张没人维护的字典表） */
    private String injuries;

    /** 偏好时段，JSON 数组字符串，如 ["WORKDAY_EVENING","WEEKEND_MORNING"] */
    @TableField("prefer_slots")
    private String preferSlots;

    /**
     * 用户在跟踪哪些指标，JSON 数组字符串，如 ["weight","bodyFatRate","waistCm","sleepHours"]。
     * <p>★这是"让用户自定义字段"的替代品：他不能发明指标，但能从全集里勾选要跟踪的。
     * 录入页只渲染勾选项、趋势切换器只列勾选项，效果覆盖自定义字段九成的真实诉求，
     * 成本是一个 JSON 列 + 一次过滤。</p>
     */
    @TableField("track_metrics")
    private String trackMetrics;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
