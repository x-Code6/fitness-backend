package com.yuedong.user.service.impl;

import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.user.constant.BodyMetric;
import com.yuedong.user.mapper.UserProfileMapper;
import com.yuedong.user.model.document.BodyRecord;
import com.yuedong.user.model.entity.UserProfile;
import com.yuedong.user.model.dto.BodyRecordSaveDTO;
import com.yuedong.user.model.vo.BodyViews;
import com.yuedong.user.service.BodyRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 身体记录实现（MongoDB）。
 *
 * <p>★用 {@link MongoTemplate} 而不是 {@code MongoRepository} 的理由：
 * Repository 只给 {@code findByXxx} 这类派生查询，写起来短，但<b>看不见 Mongo 在做什么</b>；
 * 这个功能是拿来学 Mongo 的，所以每个操作都显式写出来并逐行注释。
 * 真要按 Spring 习惯做，Repository 也够用——两者可以并存，这里只需要一种。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BodyRecordServiceImpl implements BodyRecordService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    /** 备注长度上限。超了给一句带数字的人话，而不是让 @Size 抛一堆字段名 */
    private static final int NOTE_MAX_LENGTH = 40;

    private final MongoTemplate mongoTemplate;
    /** 档案在 MySQL：趋势图的目标线要从那里取，所以本类同时碰两种存储（写清楚，不藏着） */
    private final UserProfileMapper userProfileMapper;

    // =====================================================================
    // 指标元数据
    // =====================================================================
    @Override
    public List<BodyViews.MetricOption> metrics() {
        List<BodyViews.MetricOption> options = new ArrayList<>();
        for (BodyMetric metric : BodyMetric.values()) {
            options.add(BodyViews.MetricOption.builder()
                    .code(metric.getCode())
                    .label(metric.getLabel())
                    .unit(metric.getUnit())
                    .decimals(metric.getDecimals())
                    .min(metric.getMin())
                    .max(metric.getMax())
                    .required(metric.isRequired())
                    // 胸围/臀围折叠。这个判断放在后端，前端只按字段渲染，
                    // 否则"哪几项算进阶"会在两端各写一遍
                    .advanced(BodyMetric.CHEST == metric || BodyMetric.HIP == metric)
                    .build());
        }
        return options;
    }

    // =====================================================================
    // 记一次（一天一条，重复提交即更新）
    // =====================================================================
    @Override
    public BodyViews.Record save(Long userId, BodyRecordSaveDTO dto) {
        String recordDate = resolveRecordDate(dto.getRecordDate());
        validate(dto);

        // 只把"用户真填了"的字段写进文档，没填的整个字段不存在（不是 null、更不是 0）。
        // 这一步是这套建模的全部意义所在：0 会被画进趋势图，字段缺失不会。
        Update update = new Update();
        putIfPresent(update, "weight", dto.getWeight());
        putIfPresent(update, "body_fat_rate", dto.getBodyFatRate());
        putIfPresent(update, "waist_cm", dto.getWaistCm());
        putIfPresent(update, "chest_cm", dto.getChestCm());
        putIfPresent(update, "hip_cm", dto.getHipCm());
        putIfPresent(update, "sleep_hours", dto.getSleepHours());
        putIfPresent(update, "energy_level", dto.getEnergyLevel());
        putIfPresent(update, "soreness_level", dto.getSorenessLevel());
        putIfPresent(update, "note", dto.getNote());
        // 每次保存都盖一个更新时间。列表页"今天 21:30 更新过"读它
        update.set("updated_at", LocalDateTime.now());

        // 定位条件：谁 + 哪一天。★两个条件缺一不可，只按日期匹配就成了改别人的记录
        Query query = Query.query(Criteria.where("userId").is(userId)
                .and("record_date").is(recordDate));

        // upsert = "匹配到就按上面的 Update 改，没匹配到就拿 Update 的内容 + 查询条件里的等值字段插入一条"。
        // 所以这里不需要"先查有没有 → 有就 update / 没有就 insert"那两步——
        // 那两步在并发下会双双判断成"没有"，插出两条同日的记录。
        try {
            mongoTemplate.upsert(query, update, BodyRecord.class);
        } catch (DuplicateKeyException raceLost) {
            // 唯一索引 uk_user_date 冲突 = 另一个请求刚刚把今天这条建出来了。
            // 它正是我们要的语义（一天一条），所以这里不是错误：直接按更新再走一次。
            log.info("同一天并发记录，转为更新: userId={}, date={}", userId, recordDate);
            mongoTemplate.upsert(query, update, BodyRecord.class);
        }

        return toRecord(findOneByDate(userId, recordDate), null);
    }

    // =====================================================================
    // 读
    // =====================================================================
    @Override
    public BodyViews.Record latest(Long userId) {
        // find + with(Sort) + limit：取该用户按日期倒序的第一条。
        // ★不用 findOne——findOne 不保证顺序，必须显式排序才是"最近一条"
        Query query = Query.query(Criteria.where("userId").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "record_date"))
                .limit(2);
        // 取 2 条是有意的：第 2 条用来算"比上次重了多少"，
        // 省掉前端再发一次请求，也避免两端各算一遍得出两个差值
        List<BodyRecord> rows = mongoTemplate.find(query, BodyRecord.class);
        if (rows.isEmpty()) {
            return null;
        }
        BigDecimal previous = rows.size() > 1 ? rows.get(1).getWeight() : null;
        return toRecord(rows.get(0), previous);
    }

    @Override
    public List<BodyViews.Record> list(Long userId, int days) {
        Query query = Query.query(Criteria.where("userId").is(userId));
        if (days > 0) {
            // 日期是 yyyy-MM-dd 字符串，字典序等于时间序，所以范围比较可以直接用字符串
            query.addCriteria(Criteria.where("record_date")
                    .gte(LocalDate.now().minusDays(days).format(DATE_FMT)));
        }
        query.with(Sort.by(Sort.Direction.DESC, "record_date"));

        List<BodyRecord> rows = mongoTemplate.find(query, BodyRecord.class);
        List<BodyViews.Record> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            // 上一条更旧的就是列表里它下面那一行（倒序），拿它算体重差
            BigDecimal previous = i + 1 < rows.size() ? rows.get(i + 1).getWeight() : null;
            result.add(toRecord(rows.get(i), previous));
        }
        return result;
    }

    @Override
    public boolean delete(Long userId, String recordDate) {
        Query query = Query.query(Criteria.where("userId").is(userId)
                .and("record_date").is(resolveRecordDate(recordDate)));
        // remove 返回 DeleteResult，getDeletedCount 才是"真删了几条"。
        // 不判返回值就报"删除成功"，是"用户删了但记录还在"这类投诉的来源
        long deleted = mongoTemplate.remove(query, BodyRecord.class).getDeletedCount();
        log.info("身体记录删除: userId={}, date={}, 实删={}", userId, recordDate, deleted);
        return deleted > 0;
    }

    // =====================================================================
    // 趋势
    // =====================================================================
    @Override
    public BodyViews.Trend trend(Long userId, String metric, int days) {
        BodyMetric target = BodyMetric.of(metric);
        if (target == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不支持的指标：" + metric);
        }
        String field = mongoFieldOf(target);

        // ---- 聚合管道：把"某人一段时间内、这个字段存在的记录"压成 {date,value} 序列 ----
        Aggregation aggregation = Aggregation.newAggregation(
                // ① $match：先按人和日期缩小扫描范围。★唯一索引正好服务这一步，条件顺序要一致
                Aggregation.match(Criteria.where("userId").is(userId)
                        .and("record_date").gte(LocalDate.now().minusDays(days <= 0 ? 36500 : days).format(DATE_FMT))),
                // ② 只要这个字段真的填过的记录。这一步替代了"读出来再过滤 null"，
                //    少传一堆空文档回来，也保证图上不会多出 0 值点
                Aggregation.match(Criteria.where(field).exists(true).ne(null)),
                // ③ $project：只留两个字段。趋势图不需要围度、备注这些无关列
                Aggregation.project("record_date").and(field).as("value"),
                // ④ 升序：图是从左往右画的，数据库侧排好省得在 Java 里再 sort 一次
                Aggregation.sort(Sort.Direction.ASC, "record_date"),
                // ⑤ 最多 400 个点。一天一条时够用两年；防止"全部"这个档位把整段历史拖进内存
                Aggregation.limit(400)
        );
        AggregationResults<TrendPoint> results =
                mongoTemplate.aggregate(aggregation, "body_record", TrendPoint.class);

        List<TrendPoint> raw = results.getMappedResults();
        List<BodyViews.Point> points = new ArrayList<>(raw.size());
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal max = null;
        BigDecimal min = null;
        for (TrendPoint row : raw) {
            if (row.getValue() == null) {
                continue;
            }
            points.add(BodyViews.Point.builder().recordDate(row.getDate()).value(row.getValue()).build());
            sum = sum.add(row.getValue());
            max = max == null || row.getValue().compareTo(max) > 0 ? row.getValue() : max;
            min = min == null || row.getValue().compareTo(min) < 0 ? row.getValue() : min;
        }

        int scale = target.getDecimals();
        BigDecimal latest = points.isEmpty() ? null : points.get(points.size() - 1).getValue();
        BigDecimal first = points.isEmpty() ? null : points.get(0).getValue();
        BigDecimal change = latest == null || first == null ? null : latest.subtract(first);
        BigDecimal avg = points.isEmpty() ? null
                : sum.divide(BigDecimal.valueOf(points.size()), 4, RoundingMode.HALF_UP).setScale(scale, RoundingMode.HALF_UP);

        return BodyViews.Trend.builder()
                .metric(target.getCode())
                .metricLabel(target.getLabel())
                .unit(target.getUnit())
                .decimals(target.getDecimals())
                .days(days)
                .points(points)
                .latest(latest)
                .change(change)
                .changeText(change == null ? "" : signed(change, scale))
                // 涨跌哪个颜色由后端定：减脂场景"变小"是好事，前端不该自己拿"数值<0"去猜
                .changeDirection(directionOf(target, change))
                .maxValue(max)
                .minValue(min)
                .avgValue(avg)
                .sampleCount(points.size())
                // 目标线：目前只有体重设了目标值（档案里的 target_weight），其余指标为 null，前端就不画那条线。
                // ★在这里查 MySQL 拿，而不是让前端"再请求一次档案然后自己拼"——
                //   "图上画哪条线、线标什么文案"是趋势这件事的一部分，就该在这一处算完。
                .target(targetWeightOf(userId))
                .targetText(targetWeightText(userId))
                .emptyText(points.isEmpty()
                        ? "还没有记录过" + target.getLabel() + "，去记一次就能看趋势" : null)
                .build();
    }

    /** 档案里的目标体重。读不到档案就什么也不画——宁缺毋滥，别拿 0 当默认值 */
    private BigDecimal targetWeightOf(Long userId) {
        UserProfile profile = userProfileMapper.selectById(userId);
        return profile == null ? null : profile.getTargetWeight();
    }

    private String targetWeightText(Long userId) {
        BigDecimal target = targetWeightOf(userId);
        return target == null ? null : target.stripTrailingZeros().toPlainString() + " kg";
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 聚合结果承接类。字段名要和 $project 输出的名字对得上，所以 date 不是文档里的列名 */
    @lombok.Data
    public static class TrendPoint {
        @com.fasterxml.jackson.annotation.JsonProperty("record_date")
        private String date;
        private BigDecimal value;
    }

    /** 指标 code → 文档字段名。★映射只在这一处，改字段名不用全局搜 */
    private String mongoFieldOf(BodyMetric metric) {
        switch (metric) {
            case WEIGHT:
                return "weight";
            case BODY_FAT:
                return "body_fat_rate";
            case WAIST:
                return "waist_cm";
            case CHEST:
                return "chest_cm";
            case HIP:
                return "hip_cm";
            case SLEEP:
                return "sleep_hours";
            case ENERGY:
                return "energy_level";
            case SORENESS:
            default:
                return "soreness_level";
        }
    }

    private BodyRecord findOneByDate(Long userId, String recordDate) {
        Query query = Query.query(Criteria.where("userId").is(userId)
                .and("record_date").is(recordDate));
        // findOne：只要一条，Mongo 侧就用 limit(1)。比 find 再取 get(0) 省一次游标开销
        return mongoTemplate.findOne(query, BodyRecord.class);
    }

    /** null 就整个 unset 掉。这一步保证"没填"在文档里是"没有这个字段"，而不是 null 或 0 */
    private void putIfPresent(Update update, String field, Object value) {
        if (value instanceof String) {
            if (((String) value).trim().isEmpty()) {
                update.unset(field);
                return;
            }
        }
        if (value == null) {
            update.unset(field);
            return;
        }
        update.set(field, value);
    }

    /** 日期归一：不传=今天；传了必须能解析、且不能是未来 */
    private String resolveRecordDate(String input) {
        if (input == null || input.trim().isEmpty()) {
            return LocalDate.now().format(DATE_FMT);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(input.trim(), DATE_FMT);
        } catch (Exception parseFailed) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "日期格式应为 yyyy-MM-dd");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "还不能记录未来的数据");
        }
        return date.format(DATE_FMT);
    }

    /** 必填 + 范围校验。范围取自 BodyMetric，前端拿到的是同一份，两边不会各说各话 */
    private void validate(BodyRecordSaveDTO dto) {
        requireInRange(BodyMetric.WEIGHT, dto.getWeight());
        optionalInRange(BodyMetric.BODY_FAT, dto.getBodyFatRate());
        optionalInRange(BodyMetric.WAIST, dto.getWaistCm());
        optionalInRange(BodyMetric.CHEST, dto.getChestCm());
        optionalInRange(BodyMetric.HIP, dto.getHipCm());
        optionalInRange(BodyMetric.SLEEP, dto.getSleepHours());
        optionalInRange(BodyMetric.ENERGY, dto.getEnergyLevel() == null ? null : BigDecimal.valueOf(dto.getEnergyLevel()));
        optionalInRange(BodyMetric.SORENESS, dto.getSorenessLevel() == null ? null : BigDecimal.valueOf(dto.getSorenessLevel()));
        if (dto.getNote() != null && dto.getNote().trim().length() > NOTE_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "备注最多 " + NOTE_MAX_LENGTH + " 字");
        }
    }

    private void requireInRange(BodyMetric metric, BigDecimal value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请填写" + metric.getLabel());
        }
        checkRange(metric, value);
    }

    private void optionalInRange(BodyMetric metric, BigDecimal value) {
        if (value != null) {
            checkRange(metric, value);
        }
    }

    private void checkRange(BodyMetric metric, BigDecimal value) {
        if (value.compareTo(metric.getMin()) < 0 || value.compareTo(metric.getMax()) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    metric.getLabel() + "要在 " + metric.getMin().stripTrailingZeros().toPlainString()
                            + " ~ " + metric.getMax().stripTrailingZeros().toPlainString() + " 之间");
        }
    }

    /** 文档 → 视图。previousWeight 传 null 表示"没有上一条可比"，那就不给差值 */
    private BodyViews.Record toRecord(BodyRecord row, BigDecimal previousWeight) {
        if (row == null) {
            return null;
        }
        LocalDate date = LocalDate.parse(row.getRecordDate(), DATE_FMT);
        BigDecimal change = row.getWeight() != null && previousWeight != null
                ? row.getWeight().subtract(previousWeight) : null;
        return BodyViews.Record.builder()
                .recordDate(row.getRecordDate())
                .weekdayText(weekdayOf(date))
                .weight(row.getWeight())
                .bodyFatRate(row.getBodyFatRate())
                .waistCm(row.getWaistCm())
                .chestCm(row.getChestCm())
                .hipCm(row.getHipCm())
                .sleepHours(row.getSleepHours())
                .energyLevel(row.getEnergyLevel())
                .sorenessLevel(row.getSorenessLevel())
                .note(row.getNote())
                .energyText(energyText(row.getEnergyLevel()))
                .sorenessText(sorenessText(row.getSorenessLevel()))
                .updatedText(updatedText(row.getUpdatedAt()))
                .weightChange(change)
                .weightChangeText(change == null ? "" : signed(change, 1))
                .build();
    }

    private String weekdayOf(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return WEEKDAYS[dow.getValue() - 1];
    }

    /** 数字 → 人话。这张表放后端，前端不抄一份（否则改文案要改两处） */
    private String energyText(Integer level) {
        if (level == null) {
            return null;
        }
        switch (level) {
            case 1:
                return "很差";
            case 2:
                return "偏差";
            case 3:
                return "一般";
            case 4:
                return "不错";
            default:
                return "很好";
        }
    }

    private String sorenessText(Integer level) {
        if (level == null) {
            return null;
        }
        switch (level) {
            case 0:
                return "不酸";
            case 1:
                return "轻微";
            case 2:
                return "中度";
            default:
                return "很重";
        }
    }

    private String updatedText(LocalDateTime updatedAt) {
        if (updatedAt == null) {
            return "";
        }
        if (updatedAt.toLocalDate().equals(LocalDate.now())) {
            return "今天 " + updatedAt.format(TIME_FMT);
        }
        return updatedAt.toLocalDate().format(DATE_FMT);
    }

    /** 带符号的数值文案：-0.3 / +1.5 / 0。★统一在这一处出，列表页和趋势摘要不会出现两种写法 */
    private String signed(BigDecimal value, int scale) {
        BigDecimal fixed = value.setScale(scale, RoundingMode.HALF_UP);
        String body = fixed.abs().stripTrailingZeros().toPlainString();
        if (fixed.compareTo(BigDecimal.ZERO) == 0) {
            return "持平";
        }
        return (fixed.compareTo(BigDecimal.ZERO) > 0 ? "+" : "−") + body;
    }

    /**
     * 变化方向。★为什么要后端算：颜色语义取决于"这个指标是越大越好还是越小越好"，
     * 体重/体脂/腰围是越小越好，骨骼肌、精力是越大越好，
     * 前端如果写 {@code value < 0 ? 绿 : 红}，以后加"骨骼肌"那天就一定画反。
     */
    private String directionOf(BodyMetric metric, BigDecimal change) {
        if (change == null || change.compareTo(BigDecimal.ZERO) == 0) {
            return "FLAT";
        }
        boolean decreaseIsGood = metric == BodyMetric.WEIGHT || metric == BodyMetric.BODY_FAT
                || metric == BodyMetric.WAIST || metric == BodyMetric.CHEST
                || metric == BodyMetric.HIP || metric == BodyMetric.SORENESS;
        boolean wentDown = change.compareTo(BigDecimal.ZERO) < 0;
        return wentDown == decreaseIsGood ? "GOOD" : "BAD";
    }
}
