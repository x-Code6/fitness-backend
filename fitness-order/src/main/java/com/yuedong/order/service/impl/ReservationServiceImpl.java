package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.client.CoachClient;
import com.yuedong.course.api.client.StoreClient;
import com.yuedong.course.api.dto.CoachDTO;
import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.order.constant.OrderConstants;
import com.yuedong.order.constant.ReservationStatus;
import com.yuedong.order.mapper.CoachBusyMapper;
import com.yuedong.order.mapper.EntitlementMapper;
import com.yuedong.order.mapper.ReservationMapper;
import com.yuedong.order.model.dto.ReservationCreateDTO;
import com.yuedong.order.model.entity.CoachBusy;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.entity.Reservation;
import com.yuedong.order.model.vo.ReservationVO;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.service.EntitlementService;
import com.yuedong.order.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 预约实现（时段轴）。
 *
 * <p>★<b>{@code @Transactional} 就是本类的全部并发方案。</b>
 * 预约单和占用格子都在 yd_order，一次事务里写完：抢同一格时后插入的人撞
 * {@code uk_coach_start}，异常抛出、事务回滚，<b>前面已经插成功的格子自动释放</b>，
 * 不需要手写补偿。这正是"避免分布式事务"的落地方式 —— 不是上 Seata，
 * 而是让这一步根本不需要跨服务写。</p>
 *
 * <p>对照旧实现：占名额是 {@code order → course} 的 Feign 调用，
 * 于是"落库失败要释放""用券失败要释放""超时要释放"三段补偿代码，
 * 其中释放的返回值当初根本没被检查。<b>删掉的是代码，也是 bug。</b></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 单轮结账最多处理多少张预约。★分批的理由与凭证过期任务相同：别让一次 UPDATE 锁太久 */
    private static final int SETTLE_BATCH_SIZE = 200;

    private final ReservationMapper reservationMapper;
    private final CoachBusyMapper coachBusyMapper;
    private final EntitlementMapper entitlementMapper;
    private final EntitlementService entitlementService;
    private final CoachClient coachClient;
    private final StoreClient storeClient;
    private final NotifyPublisher notifyPublisher;

    // =====================================================================
    // 创建预约（流程 C）
    // =====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO book(Long userId, ReservationCreateDTO dto) {
        // ===== 第 1 步：取凭证并校验归属 =====
        // WHERE id=? AND user_id=?。★user_id 不能省，只按 id 查就成了"猜到凭证 id 就能蹭别人的课"
        Entitlement entitlement = entitlementService.loadUsable(userId, dto.getEntitlementId());

        // ===== 第 2 步：请求与凭证必须说的是同一门课 =====
        // 不是为了"防黑客"，是防前端把课程页与预约页的 id 串错：
        // 那种情况下每一步都合法，数据却脏了（用 A 课凭证约了 B 课教练），事后极难归因
        if (!Objects.equals(dto.getCourseId(), entitlement.getCourseId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "凭证与课程不匹配");
        }

        // ===== 第 3 步：解析时间。★整点是硬前提，非整点唯一索引拦不住 =====
        LocalDateTime startTime = parseStart(dto.getDate(), dto.getStartTime());
        LocalDateTime now = LocalDateTime.now();
        if (!startTime.isAfter(now)) {
            throw new BusinessException(ErrorCode.SLOT_TIME_INVALID, "不能预约已经过去的时刻");
        }
        if (startTime.isAfter(now.plusDays(OrderConstants.SLOT_MAX_AHEAD_DAYS))) {
            throw new BusinessException(ErrorCode.SLOT_TIME_INVALID,
                    "只能预约 " + OrderConstants.SLOT_MAX_AHEAD_DAYS + " 天内的时段");
        }
        int lessonHours = entitlement.getLessonHours() == null || entitlement.getLessonHours() < 1
                ? 1 : entitlement.getLessonHours();
        LocalDateTime endTime = startTime.plusHours(lessonHours);

        // ===== 第 4 步：凭证有效期要覆盖<b>上课那一刻</b>，而不是"现在" =====
        // ★这是"有效期最后一天约晚上的课"那个场景：用 now 判会误放行，
        // 等真去上课时凭证已经过期。实体上的 usableAt(moment) 就是为此存在
        if (!entitlement.usableAt(startTime)) {
            throw new BusinessException(ErrorCode.ENTITLEMENT_TIME_CONFLICT,
                    "凭证在上课时刻会失效，请换一张或选择更早的时间");
        }

        // ===== 第 5 步：教练必须会这门课、就在这家店、且当天上班、区间放得下 =====
        CoachDTO coach = findBookableCoach(entitlement, dto.getCoachId());
        validateCoachWindow(coach, startTime, lessonHours);

        // ===== 第 6 步：同一用户同一时刻只能有一节课 =====
        // ★这一条唯一索引管不了（索引只管"一个教练一小时"），只能业务判：
        // 一次 IN 查这几个格子里有没有我自己的课
        List<LocalDateTime> cells = hourCells(startTime, lessonHours);
        Long mineAlready = coachBusyMapper.selectCount(new LambdaQueryWrapper<CoachBusy>()
                .eq(CoachBusy::getUserId, userId)
                .in(CoachBusy::getStartTime, cells));
        if (mineAlready != null && mineAlready > 0) {
            throw new BusinessException(ErrorCode.SLOT_NOT_BOOKABLE, "这个时间你已经有一节课了，请先取消或换时间");
        }

        // ===== 第 7 步：先落预约单（拿到 id 才能给格子挂 reservation_id）=====
        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        reservation.setEntitlementId(entitlement.getId());
        reservation.setOrderNo(entitlement.getOrderNo());
        reservation.setCourseId(entitlement.getCourseId());
        reservation.setStoreId(entitlement.getStoreId());
        reservation.setCoachId(coach.getCoachId());
        // ★三个名称都是快照：教练改名离职、课程下架、门店改名，历史预约单仍显示当时的名字
        reservation.setCoachName(coach.getName());
        reservation.setCourseName(entitlement.getCourseName());
        reservation.setStoreName(entitlement.getStoreName());
        reservation.setLessonHours(lessonHours);
        reservation.setStartTime(startTime);
        reservation.setEndTime(endTime);
        reservation.setStatus(ReservationStatus.BOOKED);
        // ★规则固化成数据而不是代码：取消只看这一列做 CAS，Java 里不计算"1 小时"
        reservation.setCancelDeadline(startTime.minusHours(OrderConstants.CANCEL_DEADLINE_HOURS));
        reservation.setCreatedAt(now);
        // 单号与核销码可能撞唯一索引（概率极低），撞了就换个值重试；
        // ★注意这里的重试和第 8 步格子撞键是两件不同的事：这里是"我的号码选得太巧"，
        // 重试即可；那里是"真的被别人抢先占了"，必须报错且不能重试
        insertReservationWithUniqueRetry(reservation);

        // ===== 第 8 步：按 start_time 升序逐格占位 =====
        // ★升序不是洁癖，是防死锁：两个事务交叉顺序加锁会形成环（第 3 条：可读性优先，
        // 但这条规则值得写进注释，因为将来有人把循环方向改掉就会踩）
        for (LocalDateTime cell : cells) {
            CoachBusy busy = new CoachBusy();
            busy.setCoachId(coach.getCoachId());
            busy.setStoreId(coach.getStoreId());
            busy.setStartTime(cell);
            busy.setReservationId(reservation.getId());
            busy.setUserId(userId);
            busy.setCreatedAt(now);
            // 增：占一个整点格子。撞 uk_coach_start = 这一小时刚被别人抢走，
            // 抛异常让事务回滚前面已插成功的格子与预约单，不需要手写补偿
            try {
                coachBusyMapper.insert(busy);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.SLOT_ALREADY_TAKEN);
            }
        }

        // ===== 第 9 步：站内通知（把核销码直接写在通知里，用户不用回页面找）=====
        notifyPublisher.publishBookingNotify(userId, "预约成功",
                "「" + reservation.getCourseName() + "」" + startTime.format(TIME_FMT)
                        + " 已约「" + coach.getName() + "」教练，到店出示核销码 "
                        + reservation.getVerifyCode() + "；"
                        + startTime.minusHours(OrderConstants.CANCEL_DEADLINE_HOURS).format(TIME_FMT)
                        + " 前可免费取消，取消不扣次数。");

        log.info("预约成功: reservationNo={}, userId={}, coachId={}, start={}, 占用 {} 格",
                reservation.getReservationNo(), userId, coach.getCoachId(), startTime, lessonHours);
        return toVO(reservation, entitlement, storeAddress(reservation.getStoreId()));
    }

    /**
     * 查教练会不会这门课、在不在凭证那家店。
     * <p>★一次拿全再内存找，而不是"按 id 查教练"再单独判会教什么课：
     * {@code /inner/coach/bookable} 已经把"会这门课 + 在该店 + 在岗"三个条件在课程侧过滤好了，
     * <b>口径只有一份定义</b>，比时段页少一处不一致。</p>
     */
    private CoachDTO findBookableCoach(Entitlement entitlement, Long coachId) {
        List<CoachDTO> coaches = coachClient.bookable(entitlement.getCourseId(), entitlement.getStoreId()).getData();
        if (coaches != null) {
            for (CoachDTO coach : coaches) {
                if (Objects.equals(coach.getCoachId(), coachId)) {
                    return coach;
                }
            }
        }
        throw new BusinessException(ErrorCode.COACH_NOT_QUALIFIED);
    }

    /** 校验起点落在"门店营业 ∩ 教练工时"内，且 H 个格子都在区间里 */
    private void validateCoachWindow(CoachDTO coach, LocalDateTime startTime, int lessonHours) {
        if (!worksOn(coach.getWorkDays(), startTime.toLocalDate())) {
            throw new BusinessException(ErrorCode.SLOT_NOT_BOOKABLE, coach.getName() + " 教练周"
                    + weekDayText(startTime.toLocalDate()) + "不排班");
        }
        int startHour = startTime.getHour();
        int workStart = coach.getWorkStartHour() == null ? 0 : coach.getWorkStartHour();
        int workEnd = coach.getWorkEndHour() == null ? 24 : coach.getWorkEndHour();
        if (startHour < workStart || startHour + lessonHours > workEnd) {
            throw new BusinessException(ErrorCode.SLOT_NOT_BOOKABLE,
                    "该时段超出教练可约区间（" + String.format("%02d:00-%02d:00", workStart, workEnd) + "）");
        }
    }

    /** 落预约单，单号/核销码撞唯一索引时最多换三次 */
    private void insertReservationWithUniqueRetry(Reservation reservation) {
        for (int attempt = 0; attempt < 3; attempt++) {
            reservation.setReservationNo(genReservationNo());
            reservation.setVerifyCode(genVerifyCode());
            try {
                // 增：写 t_reservation。uk_reservation_no 与 uk_verify_code 任一撞键
                // 都只是"随机数撞车"，换个值重试即可，不是业务冲突
                reservationMapper.insert(reservation);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == 2) {
                    log.error("预约单号/核销码连续三次撞键，放弃", e);
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "预约失败，请重试");
                }
            }
        }
    }

    /** 解析 "yyyy-MM-dd" + "HH:mm" 成整点时间。★非整点直接拒绝，理由见类注释 */
    private LocalDateTime parseStart(String date, String startTime) {
        LocalDate day;
        int hour;
        try {
            day = LocalDate.parse(date.trim(), DAY_FMT);
            String[] parts = startTime.trim().split(":");
            hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (minute != 0) {
                throw new IllegalArgumentException("只允许整点");
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SLOT_TIME_INVALID, "时间格式应为 yyyy-MM-dd 与 HH:00");
        }
        if (hour < 0 || hour > 23) {
            throw new BusinessException(ErrorCode.SLOT_TIME_INVALID, "时间需在 00:00~23:00 之间");
        }
        return day.atTime(hour, 0);
    }

    /** 这节课要占的连续整点列表（已按时间升序，插入顺序即锁顺序） */
    private List<LocalDateTime> hourCells(LocalDateTime startTime, int lessonHours) {
        List<LocalDateTime> cells = new ArrayList<>(lessonHours);
        for (int i = 0; i < lessonHours; i++) {
            cells.add(startTime.plusHours(i));
        }
        return cells;
    }

    // =====================================================================
    // 取消（流程 D）
    // =====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, String reservationNo) {
        // ===== 第 1 步：一句 CAS 完成状态流转与"提前 1 小时"判断 =====
        // 条件全在 SQL 里：status='BOOKED' AND cancel_deadline > NOW()。
        // ★Java 里不出现"取当前时间减一小时"，也就不依赖应用与数据库时钟严格一致
        if (reservationMapper.cancel(reservationNo, userId, LocalDateTime.now()) == 0) {
            // 影响 0 行有三种可能：单不是自己的、已完成/已取消、距开课不足 1 小时。
            // 三者给用户同一句提示（都只能找门店处理），但日志里要能分清是哪一种
            log.warn("取消预约未生效: reservationNo={}, userId={}", reservationNo, userId);
            throw new BusinessException(ErrorCode.RESERVATION_TOO_LATE);
        }

        Reservation reservation = loadOwned(userId, reservationNo);

        // ===== 第 2 步：释放它占的全部格子（★时段回到空闲，这是你要的效果）=====
        // 按 reservation_id 一次删完：命中 idx_reservation，且天然删得掉这一单的全部行；
        // 按 (coach_id, start_time) 逐格删要把时间再算一遍，算错一格就留下"幽灵占用"
        int released = coachBusyMapper.deleteByReservation(reservation.getId());

        notifyPublisher.publishBookingNotify(userId, "预约已取消",
                "「" + reservation.getCourseName() + "」" + reservation.getStartTime().format(TIME_FMT)
                        + " 的预约已取消，" + released + " 个时段已释放给其他会员。"
                        + "取消不扣次数，你的凭证还可以再约。");

        log.info("预约已取消: reservationNo={}, userId={}, 释放格子 {} 个",
                reservationNo, userId, released);
    }

    // =====================================================================
    // 到点结账（流程 D 的另一半，每分钟）
    // =====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settleDue() {
        LocalDateTime now = LocalDateTime.now();

        // 查待结账的预约：★命中 idx_settle(status, start_time) 这条专门为任务建的索引，
        // 每分钟跑一次的任务没有这条索引就是全表扫
        // 条件用 end_time（下课才算消费完），但索引用的是 start_time 那一列——
        // 索引列在前、end_time 只是回表后的过滤条件，量级下够用
        List<Reservation> due = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, ReservationStatus.BOOKED)
                .le(Reservation::getEndTime, now)
                .orderByAsc(Reservation::getEndTime)
                .last("LIMIT " + SETTLE_BATCH_SIZE));
        if (due.isEmpty()) {
            return 0;
        }

        int settled = 0;
        for (Reservation reservation : due) {
            // CAS 置 FINISHED。★影响 0 行 = 用户正好在这之前取消了，或上一轮已处理，跳过即可。
            // 这就是"重跑安全、多实例安全"，因此不需要引 ShedLock
            if (reservationMapper.markFinished(reservation.getId(), now) == 0) {
                continue;
            }
            settled++;

            // 扣一次（到点未取消 = 消费，D5）。扣次本身也是 CAS：
            // 凭证已被过期任务置终态时影响 0 行，不会把 EXPIRED 又改成 USED_UP
            int consumed = entitlementMapper.consumeOne(reservation.getEntitlementId());
            if (consumed == 0) {
                log.warn("预约已结账但凭证扣次未生效（凭证可能已过期）: reservationNo={}, entitlementId={}",
                        reservation.getReservationNo(), reservation.getEntitlementId());
            }

            notifyPublisher.publishBookingNotify(reservation.getUserId(), "课程已完成",
                    "「" + reservation.getCourseName() + "」" + reservation.getStartTime().format(TIME_FMT)
                            + " 的课程已记为完成，本次已消耗一节。");
        }

        if (settled > 0) {
            log.info("到点结账完成: 本轮 {} 张（上限 {}）", settled, SETTLE_BATCH_SIZE);
        }
        return settled;
    }

    // =====================================================================
    // 查询与管理端
    // =====================================================================
    @Override
    public List<ReservationVO> mine(Long userId, String status) {
        // 查我的预约：WHERE user_id [+ status] ORDER BY start_time
        // 命中 idx_user_status(user_id, status, start_time)——等值在前、排序在后，索引直接给有序结果
        List<Reservation> rows = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(status != null && !status.isBlank() && !"ALL".equals(status),
                        Reservation::getStatus, status)
                .orderByDesc(Reservation::getStartTime));
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }

        // ★凭证与门店都一次批量取，绝不在循环里逐条查（N 条预约 = 2 次查询 + 1 次门店）
        Map<Long, Entitlement> entitlementMap = loadEntitlements(rows);
        Map<Long, String> addressMap = loadAddresses(rows);

        List<ReservationVO> result = new ArrayList<>(rows.size());
        for (Reservation row : rows) {
            result.add(toVO(row, entitlementMap.get(row.getEntitlementId()),
                    addressMap.get(row.getStoreId())));
        }
        return result;
    }

    @Override
    public ReservationVO detail(Long userId, String reservationNo) {
        Reservation reservation = loadOwned(userId, reservationNo);
        return toVO(reservation, entitlementMapper.selectById(reservation.getEntitlementId()),
                storeAddress(reservation.getStoreId()));
    }

    @Override
    public ReservationVO verify(String verifyCode) {
        Reservation reservation = reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getVerifyCode, verifyCode));
        if (reservation == null) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND, "核销码无效");
        }
        // ★只登记 verified_at，不动 status、不扣次：D5 把核销从业务闸门降级成到店记录。
        // verified_at IS NULL 条件保证一码一次，重复扫影响 0 行
        if (reservationMapper.markVerified(verifyCode, LocalDateTime.now()) == 0) {
            throw new BusinessException(ErrorCode.RESERVATION_STATUS_ERROR,
                    "该码已核销，或预约已取消/已完成");
        }
        return toVO(reservationMapper.selectById(reservation.getId()),
                entitlementMapper.selectById(reservation.getEntitlementId()),
                storeAddress(reservation.getStoreId()));
    }

    // =====================================================================
    // VO 组装与工具
    // =====================================================================

    /** 实体 → VO。★派生字段（canCancel / 各种文案 / actions）全部后端算，前端只渲染 */
    private ReservationVO toVO(Reservation row, Entitlement entitlement, String address) {
        LocalDateTime now = LocalDateTime.now();
        boolean booked = ReservationStatus.BOOKED.equals(row.getStatus());

        List<String> actions = new ArrayList<>();
        if (booked) {
            actions.add("SHOW_CODE");
            if (row.getCancelDeadline() != null && row.getCancelDeadline().isAfter(now)) {
                actions.add("CANCEL");
            }
        } else {
            // 已完成 / 已取消：只剩"再来一单"
            actions.add("AGAIN");
        }

        String remainText = "";
        if (entitlement != null) {
            remainText = entitlement.getTotalTimes() == null ? "不限次"
                    : "剩 " + Math.max(entitlement.getTotalTimes()
                    - (entitlement.getUsedTimes() == null ? 0 : entitlement.getUsedTimes()), 0) + " 次";
        }

        return ReservationVO.builder()
                .reservationNo(row.getReservationNo())
                .courseId(row.getCourseId())
                .courseName(row.getCourseName())
                .storeId(row.getStoreId())
                .storeName(row.getStoreName())
                // 门店地址跨服务取，取不到给空串：这一行前端"没地址就不显示"，不该整单报错
                .address(address == null ? "" : address)
                .coachId(row.getCoachId())
                .coachName(row.getCoachName())
                .lessonHours(row.getLessonHours())
                .dateText(row.getStartTime().format(DAY_FMT))
                .dateLabel(dateLabel(row.getStartTime().toLocalDate()))
                .weekText(weekDayText(row.getStartTime().toLocalDate()))
                .startText(String.format("%02d:00", row.getStartTime().getHour()))
                .endText(String.format("%02d:00", row.getEndTime().getHour()))
                .fullText(row.getStartTime().format(TIME_FMT) + "-" + row.getEndTime().format(HOUR_MIN))
                .status(row.getStatus())
                .statusText(ReservationStatus.textOf(row.getStatus()))
                .verifyCode(row.getVerifyCode())
                .verified(row.getVerifiedAt() != null)
                // ★canCancel 依赖服务器时钟与 cancel_deadline 的比较，前端自己算一定出现
                //  "按钮能点、提交报无法取消"
                .canCancel(booked && row.getCancelDeadline() != null && row.getCancelDeadline().isAfter(now))
                .cancelDeadlineText(row.getCancelDeadline() == null ? ""
                        : row.getCancelDeadline().format(TIME_FMT) + " 前可取消")
                .entitlementRemainText(remainText)
                .orderNo(row.getOrderNo())
                .actions(actions)
                .build();
    }

    private static final DateTimeFormatter HOUR_MIN = DateTimeFormatter.ofPattern("HH:mm");

    /** 一次取回这批预约涉及的凭证（★不是每条预约查一次） */
    private Map<Long, Entitlement> loadEntitlements(List<Reservation> rows) {
        List<Long> ids = new ArrayList<>();
        for (Reservation row : rows) {
            if (row.getEntitlementId() != null && !ids.contains(row.getEntitlementId())) {
                ids.add(row.getEntitlementId());
            }
        }
        Map<Long, Entitlement> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (Entitlement entitlement : entitlementMapper.selectBatchIds(ids)) {
            map.put(entitlement.getId(), entitlement);
        }
        return map;
    }

    /** 一次取回涉及的门店地址：先去重再逐个取，★一个门店只调一次而不是每条预约调一次 */
    private Map<Long, String> loadAddresses(List<Reservation> rows) {
        Map<Long, String> map = new HashMap<>();
        for (Reservation row : rows) {
            Long storeId = row.getStoreId();
            if (storeId != null && !map.containsKey(storeId)) {
                map.put(storeId, storeAddress(storeId));
            }
        }
        return map;
    }

    private String storeAddress(Long storeId) {
        if (storeId == null) {
            return null;
        }
        try {
            StoreDTO store = storeClient.detail(storeId).getData();
            return store == null ? null : store.getAddress();
        } catch (Exception e) {
            // ★取地址失败不阻断预约列表：地址是锦上添花的信息，
            // 让课程服务抖一下就把用户的"我的预约"整页打崩是捡轻放重
            log.warn("取门店地址失败: storeId={}", storeId, e);
            return null;
        }
    }

    /** 按预约单号 + 用户取一条。★user_id 必带，理由同凭证查询 */
    private Reservation loadOwned(Long userId, String reservationNo) {
        Reservation reservation = reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getReservationNo, reservationNo)
                .eq(Reservation::getUserId, userId));
        if (reservation == null) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        return reservation;
    }

    /** R + 毫秒时间戳 + 3 位随机，与订单号同一套生成规则、前缀不同便于日志一眼分辨 */
    private String genReservationNo() {
        return "R" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(100, 1000);
    }

    /**
     * 10 位数字核销码。
     * <p>★用 {@code SecureRandom} 而不是 {@code ThreadLocalRandom}：核销码是<b>到店凭证</b>，
     * 可预测的码等于给别人可猜的入场券（哪怕本项目核销不扣次，也不该留这种口子）。
     * 订单号用普通随机就够了——它不是凭证。</p>
     */
    private String genVerifyCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

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

    private String weekDayText(LocalDate day) {
        return new String[]{"", "一", "二", "三", "四", "五", "六", "日"}[day.getDayOfWeek().getValue()];
    }

    private String dateLabel(LocalDate day) {
        long diff = day.toEpochDay() - LocalDate.now().toEpochDay();
        if (diff == 0) {
            return "今天";
        }
        if (diff == 1) {
            return "明天";
        }
        return "周" + weekDayText(day);
    }
}
