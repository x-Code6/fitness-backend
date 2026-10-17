package com.yuedong.user.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 保存健身档案的入参。全部可空——用户可能只填一个目标就走了，
 * 空值一律"保持原样"而不是清空，理由和购物车 update 那条一样：不传的字段不该被重置。
 */
@Data
public class UserProfileSaveDTO {

    /** LOSE_FAT / BUILD_MUSCLE / KEEP_FIT / STRENGTH */
    private String goal;

    /** NEWBIE / UNDER_1Y / OVER_1Y */
    private String experience;

    /** 身高 cm，范围 80~250（放宽到能容纳青少年与极端值，但拦住手滑输成 1760） */
    private BigDecimal heightCm;

    /** 目标体重 kg。★必须高于身高算出的合理下限？不做这种跨字段推断——它带来的误拒比误放更多 */
    private BigDecimal targetWeight;

    /** 伤病史，≤200 字 */
    private String injuries;

    /** 偏好时段枚举值列表 */
    private List<String> preferSlots;

    /** 要跟踪哪些指标，取值必须来自 GET /body-record/metrics 的 code */
    private List<String> trackMetrics;
}
