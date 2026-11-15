package com.yuedong.user.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.user.constant.BodyMetric;
import com.yuedong.user.mapper.UserProfileMapper;
import com.yuedong.user.model.document.BodyRecord;
import com.yuedong.user.model.dto.UserProfileSaveDTO;
import com.yuedong.user.model.entity.UserProfile;
import com.yuedong.user.model.vo.UserProfileVO;
import com.yuedong.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 健身档案实现。
 *
 * <p>★这里同时读 MySQL（档案本身）和 MongoDB（最近一次体重），是有意为之：
 * 档案页要显示"当前 72.2kg / 目标 71.8kg / 还差 0.4kg"，如果让前端分两次请求再拼，
 * "差多少"这个算法就出现在前端了。数据源可以两个，<b>算法只能一处</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int INJURIES_MAX_LENGTH = 200;
    /** 身高合理区间。拦的是"手滑多输一个 0"，不是医学值 */
    private static final BigDecimal HEIGHT_MIN = new BigDecimal("80");
    private static final BigDecimal HEIGHT_MAX = new BigDecimal("250");
    private static final BigDecimal WEIGHT_MIN = new BigDecimal("20");
    private static final BigDecimal WEIGHT_MAX = new BigDecimal("300");

    private static final List<String> GOALS = Arrays.asList("LOSE_FAT", "BUILD_MUSCLE", "KEEP_FIT", "STRENGTH");
    private static final List<String> EXPERIENCES = Arrays.asList("NEWBIE", "UNDER_1Y", "OVER_1Y");

    private final UserProfileMapper userProfileMapper;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public UserProfileVO get(Long userId) {
        // 查：按主键取档案（user_id 就是主键，所以这就是最快的一条查询）
        UserProfile profile = userProfileMapper.selectById(userId);
        BodyRecord latest = latestRecord(userId);
        return toVO(profile, latest);
    }

    @Override
    public UserProfileVO save(Long userId, UserProfileSaveDTO dto) {
        validate(dto);

        // 先取原值。★"传了才改"必须读旧行，否则一次只改目标的请求会把身高清空
        UserProfile profile = userProfileMapper.selectById(userId);
        boolean isNew = profile == null;
        if (isNew) {
            profile = new UserProfile();
            profile.setUserId(userId);
            profile.setTrackMetrics(toJson(BodyMetric.allCodes()));
            profile.setCreatedAt(LocalDateTime.now());
        }
        if (dto.getGoal() != null) {
            profile.setGoal(dto.getGoal());
        }
        if (dto.getExperience() != null) {
            profile.setExperience(dto.getExperience());
        }
        if (dto.getHeightCm() != null) {
            profile.setHeightCm(dto.getHeightCm());
        }
        if (dto.getTargetWeight() != null) {
            profile.setTargetWeight(dto.getTargetWeight());
        }
        if (dto.getInjuries() != null) {
            profile.setInjuries(dto.getInjuries().trim());
        }
        if (dto.getPreferSlots() != null) {
            profile.setPreferSlots(toJson(dto.getPreferSlots()));
        }
        if (dto.getTrackMetrics() != null) {
            profile.setTrackMetrics(toJson(dto.getTrackMetrics()));
        }
        profile.setUpdatedAt(LocalDateTime.now());

        if (isNew) {
            // 增：一人一行，主键就是 user_id，重复插入会直接撞主键——这就是"不会建出两份档案"的保证
            userProfileMapper.insert(profile);
        } else {
            // 改：按主键整行更新。字段是上面逐个"传了才 set"填好的，不存在误清空
            userProfileMapper.updateById(profile);
        }
        return toVO(profile, latestRecord(userId));
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 取 Mongo 里最近一次带体重的记录。★取不到返回 null，档案页显示 "--" 而不是 0 */
    private BodyRecord latestRecord(Long userId) {
        Query query = org.springframework.data.mongodb.core.query.Query
                .query(Criteria.where("userId").is(userId).and("weight").exists(true))
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "record_date"))
                .limit(1);
        // findOne：命中唯一索引 (userId, record_date) 的反向扫描，最多一条
        return mongoTemplate.findOne(query, BodyRecord.class);
    }

    private void validate(UserProfileSaveDTO dto) {
        if (dto.getGoal() != null && !GOALS.contains(dto.getGoal())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "目标不在可选范围内");
        }
        if (dto.getExperience() != null && !EXPERIENCES.contains(dto.getExperience())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "训练经验不在可选范围内");
        }
        if (dto.getHeightCm() != null
                && (dto.getHeightCm().compareTo(HEIGHT_MIN) < 0 || dto.getHeightCm().compareTo(HEIGHT_MAX) > 0)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "身高要在 80 ~ 250 cm 之间");
        }
        if (dto.getTargetWeight() != null && (dto.getTargetWeight().compareTo(WEIGHT_MIN) < 0
                || dto.getTargetWeight().compareTo(WEIGHT_MAX) > 0)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "目标体重要在 20 ~ 300 kg 之间");
        }
        if (dto.getInjuries() != null && dto.getInjuries().trim().length() > INJURIES_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "伤病描述最多 " + INJURIES_MAX_LENGTH + " 字");
        }
        if (dto.getTrackMetrics() != null) {
            for (String code : dto.getTrackMetrics()) {
                // ★跟踪项必须是全集里的 code。这一步拦住"前端自己造指标"——
                //   真让用户自定义字段要付的代价是元数据表 + 动态表单 + 动态校验，这里明确不做
                if (BodyMetric.of(code) == null) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的指标：" + code);
                }
            }
            if (dto.getTrackMetrics().isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "至少保留一项跟踪指标，否则录入页没有内容");
            }
        }
    }

    private UserProfileVO toVO(UserProfile profile, BodyRecord latestRecord) {
        BigDecimal latestWeight = latestRecord == null ? null : latestRecord.getWeight();
        List<String> trackMetrics = profile == null || profile.getTrackMetrics() == null
                // 没设过档案的人给全集，而不是空数组：空数组会让录入页变成一张空表
                ? BodyMetric.allCodes() : fromJson(profile.getTrackMetrics());
        String goal = profile == null ? null : profile.getGoal();
        String experience = profile == null ? null : profile.getExperience();
        BigDecimal heightCm = profile == null ? null : profile.getHeightCm();
        BigDecimal targetWeight = profile == null ? null : profile.getTargetWeight();

        BigDecimal bmi = calcBmi(latestWeight, heightCm);
        BigDecimal remain = latestWeight == null || targetWeight == null ? null
                : latestWeight.subtract(targetWeight);

        return UserProfileVO.builder()
                .goal(goal)
                .goalText(goalText(goal))
                .experience(experience)
                .experienceText(experienceText(experience))
                .heightCm(heightCm)
                .targetWeight(targetWeight)
                .injuries(profile == null ? null : profile.getInjuries())
                .preferSlots(profile == null || profile.getPreferSlots() == null
                        ? new ArrayList<>() : fromJson(profile.getPreferSlots()))
                .trackMetrics(trackMetrics)
                .bmi(bmi)
                .bmiText(bmi == null ? "--" : bmi.setScale(1, RoundingMode.HALF_UP).toPlainString())
                .targetRemain(remain)
                .targetRemainText(remain == null ? null
                        : remain.abs().compareTo(new BigDecimal("0.05")) <= 0 ? "已达成目标"
                        : remain.stripTrailingZeros().toPlainString() + " kg")
                .latestWeight(latestWeight)
                .latestRecordDate(latestRecord == null ? null : latestRecord.getRecordDate())
                .build();
    }

    /** BMI 现算，绝不落库：身高或体重任一改动都会让它过期 */
    private BigDecimal calcBmi(BigDecimal weightKg, BigDecimal heightCm) {
        if (weightKg == null || heightCm == null || heightCm.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal meters = heightCm.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        return weightKg.divide(meters.multiply(meters), 2, RoundingMode.HALF_UP);
    }

    private String goalText(String goal) {
        if (goal == null) {
            return "还没设目标";
        }
        switch (goal) {
            case "LOSE_FAT":
                return "减脂";
            case "BUILD_MUSCLE":
                return "增肌";
            case "KEEP_FIT":
                return "保持体能";
            case "STRENGTH":
                return "力量提升";
            default:
                return goal;
        }
    }

    private String experienceText(String experience) {
        if (experience == null) {
            return "未填写";
        }
        switch (experience) {
            case "NEWBIE":
                return "零基础";
            case "UNDER_1Y":
                return "训练 1 年内";
            case "OVER_1Y":
                return "一年以上老手";
            default:
                return experience;
        }
    }

    /** 列表 → JSON 字符串存进 MySQL 的 JSON 列。★不用 TypeHandler 是因为本项目其余地方都是手写 JSON，混两套更乱 */
    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception serializeFailed) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "数据格式有误");
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception parseFailed) {
            // 列里存坏了一行也不该让整个档案页 500，退回全集比退回空数组更可用
            log.warn("档案 JSON 解析失败，退回默认值: {}", json);
            return BodyMetric.allCodes();
        }
    }
}
