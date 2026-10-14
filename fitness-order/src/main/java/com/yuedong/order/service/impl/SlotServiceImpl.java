package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CoachClient;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.client.StoreClient;
import com.yuedong.course.api.dto.CoachDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.order.constant.OrderConstants;
import com.yuedong.order.constant.SlotReason;
import com.yuedong.order.mapper.CoachBusyMapper;
import com.yuedong.order.mapper.EntitlementMapper;
import com.yuedong.order.model.entity.CoachBusy;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.vo.slot.SlotCoachVO;
import com.yuedong.order.model.vo.slot.SlotItemVO;
import com.yuedong.order.model.vo.slot.SlotMyVO;
import com.yuedong.order.model.vo.slot.SlotPageVO;
import com.yuedong.order.service.SlotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 时段查询实现（流程 B）。
 *
 * <p><b>整体思路：一次查完 + 内存算。</b>数据库只回答"谁占了哪些整点"，
 * 分组、连续统计、最早可约时刻全都在内存里对一条小时线扫出来。
 * 于是查询侧<b>没有并发问题</b>：读到的是某一瞬间的快照，
 * 就算这一秒有人抢先约走，也只是我给的界面旧了一格，
 * 真冲突由创建预约时的 {@code uk_coach_start} 唯一索引兜住。</p>
 *
 * <p>★三次跨服务读取（课程、门店、教练）是刻意的：这一页低频（进时段页才调一次），
 * 读的是几乎不变的配置数据，加缓存反而会把"某教练刚被约走"这种秒级状态显示错。
 * <b>什么时候该省一次 RPC、什么时候不该，看的不是调用次数而是数据变化频率。</b></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final EntitlementMapper entitlementMapper;
    private final CoachBusyMapper coachBusyMapper;
    private final CourseClient courseClient;
    private final CoachClient coachClient;
    private final StoreClient storeClient;

    @Override
    public SlotPageVO slots(Long userId, Long courseId, Long storeId, String date) {
        // ===== 第 1 步：日期参数变成一天的区间（含范围校验）=====
        LocalDate day = parseDate(date);
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        // ===== 第 2 步：课程（要 lessonHours 与课程名，并确认它是可预约的 SALE 课）=====
        Result<CourseDTO> courseResult = courseClient.detail(courseId, storeId);
        CourseDTO course = courseResult.getData();
        if (courseResult.getCode() != 0 || course == null) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        if (!"SALE".equals(course.getCourseKind())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "展示型课程无需预约");
        }
        int lessonHours = course.getLessonHours() == null || course.getLessonHours() < 1
                ? 1 : course.getLessonHours();

        // ===== 第 3 步：门店（营业时间）。店被删时 detail 返回 null，整页无法渲染 =====
        // 这里宁可报错也不要画一张"营业时间未知"的页面：用户点了也约不上，白跑一趟
        StoreDTO store = storeClient.detail(storeId).getData();
        if (store == null) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }

        // ===== 第 4 步：这门课在这个店的可约教练（一次拿全，含工时与可约星期）=====
        List<CoachDTO> coaches = coachClient.bookable(courseId, storeId).getData();
        if (coaches == null) {
            coaches = new ArrayList<>();
        }

        // ===== 第 5 步：一次查完这些教练当天的全部占用格子 =====
        // WHERE coach_id IN (...) AND start_time >= 当天00:00 AND start_time < 次日00:00
        // 命中 idx_coach_time(coach_id, start_time)：等值列在前、范围列在后。N 个教练 = 1 次查询
        Map<Long, Map<LocalDateTime, Long>> busyIndex = loadBusy(coaches, dayStart, dayEnd);

        // ===== 第 6 步：我的凭证（页面级信息，只算一次，不塞进每个教练对象）=====
        List<Entitlement> mine = entitlementMapper.selectList(new LambdaQueryWrapper<Entitlement>()
                .eq(Entitlement::getUserId, userId)
                .eq(Entitlement::getCourseId, courseId)
                .eq(Entitlement::getStoreId, storeId)
                .orderByAsc(Entitlement::getId));
        SlotMyVO my = buildMy(mine);

        // ===== 第 7 步：逐个教练在内存里生成格子表 =====
        List<SlotCoachVO> coachVOs = new ArrayList<>(coaches.size());
        for (CoachDTO coach : coaches) {
            coachVOs.add(buildCoach(coach, store, day, lessonHours,
                    Boolean.TRUE.equals(my.getCanBook()), userId,
                    busyIndex.getOrDefault(coach.getCoachId(), new HashMap<>())));
        }

        return SlotPageVO.builder()
                .date(day.format(DAY_FMT))
                .dateLabel(dateLabel(day))
                .storeId(store.getId())
                .storeName(store.getName())
                .courseName(course.getName())
                .lessonHours(lessonHours)
                .openText(storeOpenText(store))
                // 规则文案由课程域的策略生成后随快照带过来（第 2 步那次 Feign 已经拿到了）。
                // ★不在这里自己拼一遍：时段页和课程详情页的规则说法必须完全一致，
                // 两处各写一份迟早会漂
                .ruleTexts(course.getRuleTexts())
                .my(my)
                .coaches(coachVOs)
                .build();
    }

    // =====================================================================
    // 占用索引
    // =====================================================================

    /**
     * 把当天所有相关占用行组织成 {@code 教练id → (整点 → 占用人 userId)}。
     *
     * <p>★值里放 userId 而不是只放一个布尔，是因为时段页要区分两种灰态：
     * {@code BOOKED}（别人占了）与 {@code SELF_USED}（我自己约的）。
     * 只记"占没占"，用户就会对着自己已经约过的格子反复研究为什么点不了。</p>
     */
    private Map<Long, Map<LocalDateTime, Long>> loadBusy(List<CoachDTO> coaches,
                                                         LocalDateTime dayStart,
                                                         LocalDateTime dayEnd) {
        Map<Long, Map<LocalDateTime, Long>> index = new HashMap<>();
        if (coaches.isEmpty()) {
            return index;
        }
        List<Long> coachIds = new ArrayList<>(coaches.size());
        for (CoachDTO coach : coaches) {
            coachIds.add(coach.getCoachId());
        }

        List<CoachBusy> rows = coachBusyMapper.selectList(new LambdaQueryWrapper<CoachBusy>()
                .in(CoachBusy::getCoachId, coachIds)
                .ge(CoachBusy::getStartTime, dayStart)
                .lt(CoachBusy::getStartTime, dayEnd));
        for (CoachBusy row : rows) {
            index.computeIfAbsent(row.getCoachId(), key -> new HashMap<>())
                    .put(row.getStartTime(), row.getUserId());
        }
        return index;
    }

    // =====================================================================
    // 页面级凭证条
    // =====================================================================

    /**
     * 组装"我的凭证"条。
     * <p>★多张同名凭证时挑<b>最早过期</b>的那张先消耗（null 视为最晚）：
     * 反正都要过期，先用快过期的，长期有效的那张留着，这是对用户最有利的顺序。</p>
     */
    private SlotMyVO buildMy(List<Entitlement> mine) {
        LocalDateTime now = LocalDateTime.now();
        List<Entitlement> usable = new ArrayList<>();
        for (Entitlement entitlement : mine) {
            if (entitlement.usableAt(now)) {
                usable.add(entitlement);
            }
        }
        if (usable.isEmpty()) {
            return SlotMyVO.builder()
                    .bought(!mine.isEmpty())
                    .entitlementId(null)
                    .canBook(false)
                    .multiCount(0)
                    // 两种情况要分开说：买过但都用完了 → 引导"再来一单"；根本没买过 → 引导"去买课"
                    .validUntilText(mine.isEmpty() ? "尚未购买本课程" : "已购凭证均不可用")
                    .build();
        }

        Entitlement pick = usable.get(0);
        for (Entitlement candidate : usable) {
            if (pick.getValidUntil() == null
                    || (candidate.getValidUntil() != null
                    && candidate.getValidUntil().isBefore(pick.getValidUntil()))) {
                pick = candidate;
            }
        }
        boolean unlimited = pick.getTotalTimes() == null;
        int used = pick.getUsedTimes() == null ? 0 : pick.getUsedTimes();

        return SlotMyVO.builder()
                .bought(true)
                .entitlementId(pick.getId())
                .totalTimes(pick.getTotalTimes())
                .usedTimes(used)
                .remainTimes(unlimited ? null : Math.max(pick.getTotalTimes() - used, 0))
                .unlimited(unlimited)
                .validUntilText(pick.getValidUntil() == null
                        ? "长期有效" : pick.getValidUntil().format(DAY_FMT) + " 前可用")
                .canBook(true)
                .multiCount(usable.size())
                .build();
    }

    // =====================================================================
    // 单个教练的格子表
    // =====================================================================

    /**
     * 为一个教练生成当天格子表，三遍扫描。
     *
     * <p>★可约区间取<b>门店营业时间 ∩ 教练工时</b>：门店 24 小时营业时上界仍受教练工时约束
     * （人总要下班），下界取 0。交集为空就是零格子，页面显示"当天无可约时段"。</p>
     */
    private SlotCoachVO buildCoach(CoachDTO coach, StoreDTO store, LocalDate day, int lessonHours,
                                   boolean canBook, Long userId, Map<LocalDateTime, Long> busyCells) {
        boolean worksToday = worksOn(coach.getWorkDays(), day);
        int openStart = is24h(store) ? 0 : Math.max(nz(store.getOpenStartHour(), 0), nz(coach.getWorkStartHour(), 0));
        int openEnd = is24h(store) ? nz(coach.getWorkEndHour(), 0)
                : Math.min(nz(store.getOpenEndHour(), 0), nz(coach.getWorkEndHour(), 0));

        boolean isToday = day.equals(LocalDate.now());
        int nowHour = LocalDateTime.now().getHour();

        // ===== 第一遍：逐小时判物理状态。★判断顺序不可调换 =====
        // 不排班 > 已过点 > 别人占 > 自己占。顺序反了会出现"明明已经过了这个点却提示已被预约"
        List<SlotCell> cells = new ArrayList<>();
        for (int hour = openStart; hour < openEnd; hour++) {
            LocalDateTime cellStart = day.atTime(hour, 0);
            String reason = null;
            if (!worksToday) {
                reason = SlotReason.CLOSED;
            } else if (isToday && hour <= nowHour) {
                reason = SlotReason.PASSED;
            } else {
                Long holder = busyCells.get(cellStart);
                if (holder != null) {
                    reason = holder.equals(userId) ? SlotReason.SELF_USED : SlotReason.BOOKED;
                }
            }
            cells.add(new SlotCell(cellStart, reason));
        }

        // ===== 第二遍：按 lessonHours 划组，整组空闲才可约 =====
        // 这一步是 D3"一节课不可拆分"落到前端的唯一保障：
        // "18:00 空、19:00 被占"时 18:00 那格必须点不了，否则约到一半没地方上课
        int groupCount = (cells.size() + lessonHours - 1) / lessonHours;
        for (int g = 0; g < groupCount; g++) {
            boolean groupBookable = true;
            for (int i = 0; i < lessonHours; i++) {
                int idx = g * lessonHours + i;
                // 组被尾部截断（不足 lessonHours 格）→ 这一组约不了，别让用户点进去才发现
                if (idx >= cells.size() || cells.get(idx).reason != null) {
                    groupBookable = false;
                    break;
                }
            }
            for (int i = 0; i < lessonHours && g * lessonHours + i < cells.size(); i++) {
                SlotCell cell = cells.get(g * lessonHours + i);
                cell.groupIndex = g;
                cell.groupStart = i == 0;
                cell.groupBookable = groupBookable;
                // ★available 三条件同时成立：组首 + 整组空闲 + 我有可用凭证
                cell.available = groupBookable && cell.groupStart && canBook;
            }
        }

        // ===== 第三遍：翻成 VO，顺手补齐"物理空着却点不了"的两种灰态 =====
        int freeSlots = 0;
        int maxRun = 0;
        int run = 0;
        String nextFreeAt = null;
        List<SlotItemVO> items = new ArrayList<>(cells.size());
        for (SlotCell cell : cells) {
            boolean free = cell.reason == null;
            if (free) {
                freeSlots++;
                run++;
                maxRun = Math.max(maxRun, run);
                if (nextFreeAt == null) {
                    nextFreeAt = hhmm(cell.start);
                }
            } else {
                run = 0;
            }

            String finalReason = cell.reason;
            if (!cell.available && finalReason == null) {
                // 物理上空着却点不了，只剩两种可能：整组放不下 / 我没有凭证
                finalReason = canBook ? SlotReason.GROUP_UNAVAILABLE : SlotReason.NO_ENTITLEMENT;
            }
            items.add(SlotItemVO.builder()
                    .startText(hhmm(cell.start))
                    .endText(hhmm(cell.start.plusHours(1)))
                    .available(cell.available)
                    .reason(finalReason)
                    .reasonText(SlotReason.textOf(finalReason))
                    .groupIndex(cell.groupIndex)
                    .groupSize(lessonHours)
                    .groupStart(cell.groupStart)
                    .groupBookable(cell.groupBookable)
                    .build());
        }

        return SlotCoachVO.builder()
                .coachId(coach.getCoachId())
                .coachName(coach.getName())
                .coachTitle(coach.getTitle())
                .avatarColor(coach.getAvatarColor())
                .openText(rangeText(openStart, openEnd))
                .totalSlots(cells.size())
                .freeSlots(freeSlots)
                .maxContinuousFree(maxRun)
                .nextFreeAt(nextFreeAt)
                .slots(items)
                .build();
    }

    /**
     * 格子的中间状态，只在三遍扫描之间传值，不出 VO。
     * <p>★为什么不直接用 SlotItemVO 传：它的 {@code reason} 要等"我有凭证吗"这个信息
     * 确定后才能定稿，中途改一个对外字段的名字会让人以为它是最终值。
     * 内部类和对外契约分开，改一个不会影响另一个。</p>
     */
    private static class SlotCell {
        private final LocalDateTime start;
        private final String reason;
        private int groupIndex;
        private boolean groupStart;
        private boolean groupBookable;
        private boolean available;

        private SlotCell(LocalDateTime start, String reason) {
            this.start = start;
            this.reason = reason;
        }
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    /**
     * 解析日期参数。★只允许"今天起 30 天内"：再往后的占用没有意义
     * （教练工时随时会被管理员改），而且任意日期等于给外部一个
     * "想扫多少天就扫多少行"的查询口子。
     */
    private LocalDate parseDate(String date) {
        LocalDate day;
        try {
            day = (date == null || date.isBlank()) ? LocalDate.now() : LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SLOT_TIME_INVALID, "日期格式应为 yyyy-MM-dd");
        }
        LocalDate today = LocalDate.now();
        if (day.isBefore(today) || day.isAfter(today.plusDays(OrderConstants.SLOT_MAX_AHEAD_DAYS))) {
            throw new BusinessException(ErrorCode.SLOT_TIME_INVALID,
                    "只能预约今天起 " + OrderConstants.SLOT_MAX_AHEAD_DAYS + " 天内的时段");
        }
        return day;
    }

    /** work_days 形如 "1,2,3,4,5,6,7"（1=周一）。★Java 的 DayOfWeek 本来就是 1=周一，无需换算 */
    private boolean worksOn(String workDays, LocalDate day) {
        if (workDays == null || workDays.isBlank()) {
            return false;
        }
        String dow = String.valueOf(day.getDayOfWeek().getValue());
        for (String part : workDays.split(",")) {
            if (part.trim().equals(dow)) {
                return true;
            }
        }
        return false;
    }

    private boolean is24h(StoreDTO store) {
        return store.getIs24h() != null && store.getIs24h() == 1;
    }

    private int nz(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String hhmm(LocalDateTime time) {
        return String.format("%02d:00", time.getHour());
    }

    private String rangeText(int start, int end) {
        return String.format("%02d:00 - %02d:00", start, end);
    }

    private String storeOpenText(StoreDTO store) {
        if (is24h(store)) {
            return "00:00 - 24:00";
        }
        if (store.getOpenHoursText() != null && !store.getOpenHoursText().isBlank()) {
            // 展示文案优先用管理员配的那一句，与门店详情页一致（两处显示不同会更可疑）
            return store.getOpenHoursText();
        }
        return rangeText(nz(store.getOpenStartHour(), 0), nz(store.getOpenEndHour(), 24));
    }

    /** "今天/明天/后天/周X"。★后端算：前端各页面自己换算星期容易和服务器时区不一致 */
    private String dateLabel(LocalDate day) {
        long diff = day.toEpochDay() - LocalDate.now().toEpochDay();
        if (diff == 0) {
            return "今天";
        }
        if (diff == 1) {
            return "明天";
        }
        if (diff == 2) {
            return "后天";
        }
        return new String[]{"" , "周一", "周二", "周三", "周四", "周五", "周六", "周日"}[day.getDayOfWeek().getValue()];
    }
}
