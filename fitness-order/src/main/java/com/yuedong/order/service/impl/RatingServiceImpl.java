package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.order.api.dto.RatingStatsDTO;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.constant.ReservationStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.mapper.CoachRatingStatMapper;
import com.yuedong.order.mapper.CourseRatingStatMapper;
import com.yuedong.order.mapper.RatingMapper;
import com.yuedong.order.mapper.ReservationMapper;
import com.yuedong.order.mapper.StoreRatingStatMapper;
import com.yuedong.order.model.dto.RatingSaveDTO;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.entity.CoachRatingStat;
import com.yuedong.order.model.entity.CourseRatingStat;
import com.yuedong.order.model.entity.Rating;
import com.yuedong.order.model.entity.Reservation;
import com.yuedong.order.model.entity.StoreRatingStat;
import com.yuedong.order.model.vo.RatingVO;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.service.EntitlementService;
import com.yuedong.order.service.RatingService;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 评价实现（流程 E）。
 *
 * <p>★三件值得单独说的事：</p>
 * <ol>
 *   <li><b>门槛</b>：口径是"这单的凭证全部用完或过期"才可评价（已确认）。
 *       只按"已支付"放行会出现<b>一节课没上就先打一星</b>，而评分要参与门店排序，
 *       这种分的杀伤力是长期的；</li>
 *   <li><b>统计与明细同事务</b>：一条语句的原子 UPSERT 增减，不做"读-改-写"；</li>
 *   <li><b>贝叶斯只算不存</b>：C/M 是配置项，落库就锁死了参数，调一次要刷全表。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RatingServiceImpl implements RatingService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 统计类型：门店 */
    private static final String TYPE_STORE = "STORE";
    /** 统计类型：课程 */
    private static final String TYPE_COURSE = "COURSE";
    /** 统计类型：教练 */
    private static final String TYPE_COACH = "COACH";

    private final RatingMapper ratingMapper;
    private final StoreRatingStatMapper storeRatingStatMapper;
    private final CourseRatingStatMapper courseRatingStatMapper;
    private final CoachRatingStatMapper coachRatingStatMapper;
    private final BookingMapper bookingMapper;
    private final ReservationMapper reservationMapper;
    private final EntitlementService entitlementService;
    private final UserClient userClient;
    private final NotifyPublisher notifyPublisher;

    /**
     * 贝叶斯平滑的 C（可信样本数）。
     * <p>含义是"要攒够 C 条评价，你的分数才不被往均值上拽"。取 20 是常见起点：
     * 演示数据量下一条评价都不会让门店冲榜。<b>改这一个数就是改新店的收缩强度，
     * 不需要刷任何历史数据</b>——这正是分数不落库的理由。</p>
     */
    @Value("${yd.rating.bayes-c:20}")
    private double bayesC;

    /** 贝叶斯平滑的 M（全站先验均值）。演示环境写死，真实项目应由定时任务算出来回填 */
    @Value("${yd.rating.bayes-m:4.5}")
    private double bayesM;

    // =====================================================================
    // 提交评价
    // =====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Long userId, RatingSaveDTO dto) {
        // ===== 第 1 步：订单归属 + 状态 =====
        Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getOrderNo, dto.getOrderNo())
                .eq(Booking::getUserId, userId));
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        if (!BookingStatus.PAID.equals(booking.getStatus())) {
            throw new BusinessException(ErrorCode.RATING_ORDER_UNQUALIFIED, "只有已支付的订单可以评价");
        }

        // ===== 第 2 步：门槛——凭证全部结束（用完或过期）才可评 =====
        if (!entitlementService.isOrderFinished(dto.getOrderNo())) {
            throw new BusinessException(ErrorCode.RATING_ORDER_UNQUALIFIED,
                    "这节课还没上完（凭证还有剩余），上完再来评价");
        }

        // ===== 第 3 步：选的教练必须真的在这单的已完成预约里出现过 =====
        // ★不校验就是"给一个没教过我的教练刷好评"，而这个分会进教练排序
        Rating rating = new Rating();
        rating.setOrderNo(dto.getOrderNo());
        rating.setUserId(userId);
        rating.setCourseId(booking.getCourseId());
        rating.setStoreId(booking.getStoreId());
        rating.setScore(dto.getScore());
        rating.setComment(dto.getComment());
        rating.setStatus(1);
        rating.setCreatedAt(LocalDateTime.now());
        if (dto.getCoachId() != null) {
            List<Reservation> finished = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                    .eq(Reservation::getOrderNo, dto.getOrderNo())
                    .eq(Reservation::getUserId, userId)
                    .eq(Reservation::getStatus, ReservationStatus.FINISHED));
            String coachName = null;
            for (Reservation reservation : finished) {
                if (Objects.equals(reservation.getCoachId(), dto.getCoachId())) {
                    coachName = reservation.getCoachName();
                    break;
                }
            }
            if (coachName == null) {
                throw new BusinessException(ErrorCode.RATING_COACH_MISMATCH);
            }
            rating.setCoachId(dto.getCoachId());
            // 教练名用预约单里的快照，而不是再去课程服务查一次：
            // 用户评的就是"那节课的那位教练"，教练后来改名不该影响这条历史评价
            rating.setCoachName(coachName);
        }

        try {
            // 增：写 t_rating。uk_order 撞键 = 这单已经评过了（并发双击也在这里被拦死）
            ratingMapper.insert(rating);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.RATING_DUPLICATE);
        }

        // ===== 第 4 步：同事务原子累加统计（★明细与统计一起提交，对不上只有一个入口能解释）=====
        addStoreStat(booking.getStoreId(), dto.getScore(), 1);
        addCourseStat(booking.getCourseId(), dto.getScore(), 1);
        if (rating.getCoachId() != null) {
            addCoachStat(rating.getCoachId(), dto.getScore(), 1);
        }

        notifyPublisher.publishBookingNotify(userId, "评价成功",
                "感谢你对「" + booking.getCourseName() + "」的 " + dto.getScore() + " 星评价，"
                        + "评价一经提交不可修改，如需处理请联系门店。");
        log.info("评价已提交: orderNo={}, userId={}, score={}, coachId={}",
                dto.getOrderNo(), userId, dto.getScore(), dto.getCoachId());
    }

    // =====================================================================
    // 删除评价（软删）
    // =====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long ratingId) {
        // 先查出来：回退统计要用<b>当时那条评价的分数</b>，不能用前端传来的数（可以传 -5 把分刷上去）
        Rating rating = ratingMapper.selectOne(new LambdaQueryWrapper<Rating>()
                .eq(Rating::getId, ratingId)
                .eq(Rating::getUserId, userId)
                .eq(Rating::getStatus, 1));
        if (rating == null) {
            throw new BusinessException(ErrorCode.RATING_NOT_FOUND);
        }
        // 改：status 1→0。带 status=1 条件，重复删除影响 0 行，不会把统计回退两遍
        if (ratingMapper.softDelete(ratingId, userId) == 0) {
            throw new BusinessException(ErrorCode.RATING_NOT_FOUND);
        }

        // 回退三张汇总（★用原 score 取负）
        addStoreStat(rating.getStoreId(), -rating.getScore(), -1);
        addCourseStat(rating.getCourseId(), -rating.getScore(), -1);
        if (rating.getCoachId() != null) {
            addCoachStat(rating.getCoachId(), -rating.getScore(), -1);
        }
        log.info("评价已软删并回退统计: ratingId={}, orderNo={}, score={}",
                ratingId, rating.getOrderNo(), rating.getScore());
        // ★注意：行保留着，所以这单<b>永远无法再评一次</b>。
        // 这就是"可删不可改"的实现方式——真删行会让唯一键失效，删了重评等于可以无限改。
    }

    // =====================================================================
    // 查询
    // =====================================================================
    @Override
    public PageDTO<RatingVO> courseRatings(Long userId, Long courseId, Integer page, Integer size) {
        // 查某课的评价：WHERE course_id=? AND status=1 ORDER BY id DESC
        // 命中 idx_course(course_id, status, created_at)。★软删的在这里就被排除了
        Page<Rating> result = ratingMapper.selectPage(
                new Page<>(page == null || page < 1 ? 1 : page,
                        size == null || size < 1 ? 10 : Math.min(size, 50)),
                new LambdaQueryWrapper<Rating>()
                        .eq(Rating::getCourseId, courseId)
                        .eq(Rating::getStatus, 1)
                        .orderByDesc(Rating::getId));

        // 昵称与头像配色在 yd_user。★一次批量取，不在循环里逐条查
        List<Long> userIds = new ArrayList<>();
        for (Rating row : result.getRecords()) {
            if (!userIds.contains(row.getUserId())) {
                userIds.add(row.getUserId());
            }
        }
        Map<Long, UserBriefDTO> briefs = loadUserBriefs(userIds);

        List<RatingVO> rows = new ArrayList<>(result.getRecords().size());
        for (Rating row : result.getRecords()) {
            rows.add(toVO(row, briefs, userId));
        }
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public List<RatingVO> mine(Long userId) {
        List<Rating> rows = ratingMapper.selectList(new LambdaQueryWrapper<Rating>()
                .eq(Rating::getUserId, userId)
                .eq(Rating::getStatus, 1)
                .orderByDesc(Rating::getId));
        List<Long> userIds = new ArrayList<>();
        for (Rating row : rows) {
            userIds.add(row.getUserId());
        }
        Map<Long, UserBriefDTO> briefs = loadUserBriefs(userIds);
        List<RatingVO> result = new ArrayList<>(rows.size());
        for (Rating row : rows) {
            result.add(toVO(row, briefs, userId));
        }
        return result;
    }

    @Override
    public Set<String> ratedOrderNos(Long userId, List<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return new HashSet<>();
        }
        // 一次 IN 查回"这些单里哪些评过"。★给"待评价"tab 用，绝不能逐单 EXISTS
        return new HashSet<>(ratingMapper.selectRatedOrderNos(userId, orderNos));
    }

    @Override
    public List<RatingStatsDTO> stats(String type, List<Long> ids) {
        List<RatingStatsDTO> result = new ArrayList<>();
        if (type == null || ids == null || ids.isEmpty()) {
            return result;
        }
        if (TYPE_STORE.equalsIgnoreCase(type)) {
            for (StoreRatingStat stat : storeRatingStatMapper.selectList(
                    new LambdaQueryWrapper<StoreRatingStat>().in(StoreRatingStat::getTargetId, ids))) {
                result.add(toStats(stat.getTargetId(), stat.getRatingSum(), stat.getRatingCount()));
            }
        } else if (TYPE_COURSE.equalsIgnoreCase(type)) {
            for (CourseRatingStat stat : courseRatingStatMapper.selectList(
                    new LambdaQueryWrapper<CourseRatingStat>().in(CourseRatingStat::getTargetId, ids))) {
                result.add(toStats(stat.getTargetId(), stat.getRatingSum(), stat.getRatingCount()));
            }
        } else if (TYPE_COACH.equalsIgnoreCase(type)) {
            for (CoachRatingStat stat : coachRatingStatMapper.selectList(
                    new LambdaQueryWrapper<CoachRatingStat>().in(CoachRatingStat::getTargetId, ids))) {
                result.add(toStats(stat.getTargetId(), stat.getRatingSum(), stat.getRatingCount()));
            }
        } else {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "统计类型只能是 STORE / COURSE / COACH");
        }
        return result;
    }

    // =====================================================================
    // 统计原子增减与派生分
    // =====================================================================

    /** 原子 UPSERT 门店统计。★一条语句完成，读-改-写在并发下会少算 */
    private void addStoreStat(Long storeId, int deltaSum, int deltaCount) {
        if (storeId != null) {
            storeRatingStatMapper.addScore(storeId, deltaSum, deltaCount);
        }
    }

    private void addCourseStat(Long courseId, int deltaSum, int deltaCount) {
        if (courseId != null) {
            courseRatingStatMapper.addScore(courseId, deltaSum, deltaCount);
        }
    }

    private void addCoachStat(Long coachId, int deltaSum, int deltaCount) {
        if (coachId != null) {
            coachRatingStatMapper.addScore(coachId, deltaSum, deltaCount);
        }
    }

    /**
     * 组装统计结果：<b>展示分给算术平均，排序分给贝叶斯</b>，两个都算出来带出去。
     *
     * <p>★为什么要两个：只有一个"分数"就会两头不讨好——
     * 给用户看贝叶斯分会出现"5 星却显示 4.2"，
     * 给排序用算术平均则"1 个人打 5 星的店排第一"和"1 个人打 1 星就把店踩死"
     * 两个方向同时发生。</p>
     */
    private RatingStatsDTO toStats(Long targetId, Integer sum, Integer count) {
        int safeSum = sum == null ? 0 : sum;
        int safeCount = count == null ? 0 : count;
        if (safeCount <= 0) {
            // 一条评价都没有：两个分都给 null，让调用方区分"没人评"和"评了 0 分"
            return RatingStatsDTO.builder().targetId(targetId)
                    .ratingSum(0).ratingCount(0).avgText(null).bayesianScore(null).build();
        }
        double avg = (double) safeSum / safeCount;
        double bayes = (bayesC * bayesM + safeSum) / (bayesC + safeCount);
        return RatingStatsDTO.builder()
                .targetId(targetId)
                .ratingSum(safeSum)
                .ratingCount(safeCount)
                .avgText(String.format("%.1f", avg))
                .bayesianScore(bayes)
                .build();
    }

    /** 批量取用户简况。★取失败只影响"显示昵称"，不能让评价列表整页打不开 */
    private Map<Long, UserBriefDTO> loadUserBriefs(List<Long> userIds) {
        Map<Long, UserBriefDTO> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        try {
            Result<List<UserBriefDTO>> result = userClient.batch(new BatchUserQueryDTO(userIds));
            if (result.getCode() == 0 && result.getData() != null) {
                for (UserBriefDTO brief : result.getData()) {
                    map.put(brief.getId(), brief);
                }
            }
        } catch (Exception e) {
            log.warn("取评价人昵称失败，评价列表继续返回（昵称留空）: userIds={}", userIds, e);
        }
        return map;
    }

    private RatingVO toVO(Rating rating, Map<Long, UserBriefDTO> briefs, Long currentUserId) {
        UserBriefDTO brief = briefs.get(rating.getUserId());
        return RatingVO.builder()
                .ratingId(rating.getId())
                .orderNo(rating.getOrderNo())
                .courseId(rating.getCourseId())
                .userId(rating.getUserId())
                .userName(brief == null ? "会员" : brief.getNickname())
                .avatarColor(brief == null ? "0" : brief.getAvatarColor())
                .score(rating.getScore())
                .comment(rating.getComment())
                .coachId(rating.getCoachId())
                .coachName(rating.getCoachName())
                .createdText(rating.getCreatedAt() == null ? "" : rating.getCreatedAt().format(TIME_FMT))
                .agoText(agoText(rating.getCreatedAt()))
                .mine(Objects.equals(rating.getUserId(), currentUserId))
                .build();
    }

    /** "3 天前"。★用服务器时钟算，前端拿自己时钟减会出现"0 小时前"甚至负数 */
    private String agoText(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        long minutes = Duration.between(time, LocalDateTime.now()).toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        if (minutes < 24 * 60) {
            return minutes / 60 + " 小时前";
        }
        return minutes / (24 * 60) + " 天前";
    }
}
