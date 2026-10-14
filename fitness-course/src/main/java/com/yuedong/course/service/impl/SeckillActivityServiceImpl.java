package com.yuedong.course.service.impl;

import com.yuedong.common.constant.RedisKeyConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.course.api.dto.SeckillAdminDTO;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import com.yuedong.course.api.dto.SeckillSaveDTO;
import com.yuedong.course.mapper.CourseMapper;
import com.yuedong.course.mapper.SeckillActivityMapper;
import com.yuedong.course.mapper.StoreMapper;
import com.yuedong.course.model.entity.Course;
import com.yuedong.course.model.entity.SeckillActivity;
import com.yuedong.course.model.entity.Store;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.course.service.CourseService;
import com.yuedong.course.service.SeckillActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 秒杀活动实现。
 *
 * <p>库存预热模型：活动开始前，DB.remain → Redis String（seckill:stock:{活动id}）；
 * 抢购期间订单服务用 Lua 在 Redis 原子扣减（第一现场），
 * 消费端建单成功后回调本服务 deductRemain 让 DB 慢慢追平（最终一致）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillActivityServiceImpl implements SeckillActivityService {


    private final SeckillActivityMapper activityMapper;
    private final CourseMapper courseMapper;
    /** 只为管理端列表把 storeId 翻成门店名（一次查全表，不在循环里查） */
    private final StoreMapper storeMapper;
    private final StringRedisTemplate redis;
    /**
     * 课程 VO 的组装统一由 {@link CourseService} 负责。
     * <p>以前这里手抄了一遍 {@code CourseVO.builder()...}，于是课程 VO 一改字段
     * 就得在两处同步——秒杀页和课程列表显示不一致就是这么来的。
     * 注入接口而不是注入实现：{@code CourseServiceImpl} 依赖的是本类的 Mapper，
     * 不依赖本服务，所以不构成循环。</p>
     */
    private final CourseService courseService;


    /** 进行中的秒杀课程列表（前端秒杀页 / 首页横幅） */
    @Override
    public List<CourseVO> listActive() {
        List<SeckillActivity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getStatus, 1));
        if (activities.isEmpty()) {
            return List.of();
        }

        // 活动按 courseId 归组。★不用 Collectors.toMap：同一门课配了两个"进行中"活动时
        // 它会抛 IllegalStateException("Duplicate key")，把整个秒杀页打成 500。
        // 这里保留 id 较大的那个（较新的一次活动）
        Map<Long, SeckillActivity> activityMap = new HashMap<>();
        for (SeckillActivity activity : activities) {
            SeckillActivity existed = activityMap.get(activity.getCourseId());
            if (existed == null || activity.getId() > existed.getId()) {
                activityMap.put(activity.getCourseId(), activity);
            }
        }

        // 一次批量查课程，绝不在循环里 selectById
        List<CourseVO> result = new ArrayList<>();
        for (Course course : courseMapper.selectBatchIds(activityMap.keySet())) {
            SeckillActivity activity = activityMap.get(course.getId());
            CourseVO vo = courseService.buildCourseVO(course, activity);

            // 库存展示以 Redis 实时值为准（DB.remain 是账本，消费者还没追上时它会偏大）
            Integer liveRemain = readLiveStock(activity.getId());
            vo.setSeckill(CourseVO.SeckillInfo.builder()
                    .activityId(activity.getId())
                    .price(activity.getSeckillPrice())
                    .priceText(String.format("¥%.2f", activity.getSeckillPrice()))
                    .total(activity.getTotal())
                    .remain(liveRemain == null ? activity.getRemain() : liveRemain)
                    .endTime(activity.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    .build());
            result.add(vo);
        }
        return result;
    }

    /**
     * 读 Redis 里的实时剩余份数。
     *
     * <p>★旧实现是 {@code Integer.parseInt(stock)}，两种情况会炸：
     * key 不存在（活动刚下架或 Redis 被清）返回 null → NPE；
     * 值被写坏成非数字 → NumberFormatException。
     * 这里两种都退回 null，由调用方用 DB 的 remain 兜底——
     * <b>缓存只该用于"更准"，不该因为缓存异常让整个接口 500</b>。</p>
     */
    private Integer readLiveStock(Long activityId) {
        String stock = redis.opsForValue().get(RedisKeyConstants.seckillStock(activityId));
        if (stock == null || stock.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(stock.trim());
        } catch (NumberFormatException e) {
            log.warn("秒杀库存缓存内容异常，回退 DB 剩余值: activityId={}, value={}", activityId, stock);
            return null;
        }
    }

    @Override
    public SeckillActiveDTO activeSeckill(Long courseId) {
        SeckillActivity a = activityMapper.selectOne(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getCourseId, courseId)
                        .eq(SeckillActivity::getStatus, 1));
        if (a == null) {
            return null;
        }
        // endTime 必填：订单侧用它计算"一人一单"资格集合的 TTL（缺失会 NPE）
        // storeId 必填：订单要认店（凭证之后只能在该店约教练）
        return SeckillActiveDTO.builder()
                .activityId(a.getId())
                .courseId(a.getCourseId())
                .storeId(a.getStoreId())
                .seckillPrice(a.getSeckillPrice())
                .endTime(a.getEndTime() == null ? null
                        : a.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build();
    }

    @Override
    public boolean deductRemain(Long activityId) {
        return activityMapper.deductRemain(activityId) > 0;
    }

    // =====================================================================
    // 管理端
    // =====================================================================
    @Override
    public List<SeckillAdminDTO> adminList() {
        // 一次查回课程与门店做名称映射（★不在循环里查库）
        Map<Long, Course> courseMap = courseMapper.selectList(null).stream()
                .collect(Collectors.toMap(Course::getId, Function.identity()));
        Map<Long, Store> storeMap = storeMapper.selectList(null).stream()
                .collect(Collectors.toMap(Store::getId, Function.identity()));

        return activityMapper.selectList(new LambdaQueryWrapper<SeckillActivity>()
                        .orderByDesc(SeckillActivity::getId))
                .stream()
                .map(a -> SeckillAdminDTO.builder()
                        .id(a.getId())
                        .courseId(a.getCourseId())
                        .courseName(courseMap.get(a.getCourseId()) != null
                                ? courseMap.get(a.getCourseId()).getName() : "已删除课程")
                        .storeId(a.getStoreId())
                        .storeName(storeMap.get(a.getStoreId()) != null
                                ? storeMap.get(a.getStoreId()).getName() : "门店已删除")
                        .seckillPrice(a.getSeckillPrice())
                        .total(a.getTotal())
                        .remain(a.getRemain())
                        .startTime(a.getStartTime())
                        .endTime(a.getEndTime())
                        .status(a.getStatus())
                        .build())
                .toList();
    }

    @Override
    public void adminSave(SeckillSaveDTO dto) {
        // 同一课程同时只允许一场进行中的活动
        Long active = activityMapper.selectCount(new LambdaQueryWrapper<SeckillActivity>()
                .eq(SeckillActivity::getCourseId, dto.getCourseId())
                .eq(SeckillActivity::getStatus, 1));
        if (active > 0) {
            throw new com.yuedong.common.exception.BusinessException(
                    com.yuedong.common.result.ErrorCode.PARAM_ERROR, "该课程已有进行中的秒杀活动");
        }

        LocalDateTime start = parseTime(dto.getStartTime());
        LocalDateTime end = parseTime(dto.getEndTime());
        // 时间格式是字符串入参（管理端表单就是这个格式），解析失败要给的是"哪一行填错了"，
        // 而不是让 DateTimeParseException 冒出来变成 500
        if (start == null || end == null || !end.isAfter(start)) {
            throw new com.yuedong.common.exception.BusinessException(
                    com.yuedong.common.result.ErrorCode.PARAM_ERROR, "结束时间必须晚于开始时间");
        }

        SeckillActivity a = new SeckillActivity();
        a.setCourseId(dto.getCourseId());
        a.setStoreId(dto.getStoreId());
        a.setSeckillPrice(dto.getSeckillPrice());
        a.setTotal(dto.getTotal());
        a.setRemain(dto.getTotal());
        a.setStartTime(start);
        a.setEndTime(end);
        // 先按"待开始"落库：到点由扫描任务预热库存并翻状态。
        // ★开始时间已过的活动也会被扫描任务捞到并立即补发（见 scanAndSwitch 的 le 条件），
        //   所以不存在"配晚了就永远不发放"的情况
        a.setStatus(0);
        activityMapper.insert(a);
        log.info("管理端创建秒杀活动: id={}, courseId={}, storeId={}, total={}",
                a.getId(), a.getCourseId(), a.getStoreId(), a.getTotal());
    }

    /** 把管理端传来的 "yyyy-MM-dd HH:mm:ss" 转成 LocalDateTime；格式不对返回 null 由调用方判 */
    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim().replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("秒杀时间格式不正确: {}", text);
            return null;
        }
    }

    @Override
    public void adminEnd(Long activityId) {
        SeckillActivity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new com.yuedong.common.exception.BusinessException(
                    com.yuedong.common.result.ErrorCode.PARAM_ERROR, "活动不存在");
        }
        a.setStatus(2);
        a.setEndTime(LocalDateTime.now()); // 提前结束：截止时间改为现在
        activityMapper.updateById(a);
        log.info("管理端提前结束秒杀活动: id={}", activityId);
    }

    /**
     * 预热：把 DB 的剩余份数写进 Redis，作为抢购的扣减起点。
     *
     * <p>★这里改掉一个真 bug：旧实现预热时会 {@code delete(seckill:bought:{活动id})}。
     * 那是"一人一单"的资格账本——重复预热（多实例、或管理员手动再点一次）会把已抢过的人
     * 的资格一起清掉，于是<b>同一个人能再抢一次</b>。库存 SET 是幂等的，
     * 资格集合却绝不能被清，所以这里只写库存。</p>
     *
     * <p>资格集合的清理交给 TTL（活动结束时自然过期，见 seckill.lua 的 EXPIRE）。</p>
     *
     * @return 写入是否成功（活动不存在或没有结束时间时返回 false）
     */
    @Override
    public boolean preheat(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getEndTime() == null) {
            log.warn("秒杀预热跳过：活动不存在或没有结束时间, activityId={}", activityId);
            return false;
        }

        // TTL 取"距活动结束还有多久"，活动结束后 Redis 自己回收，不留垃圾 key
        java.time.Duration ttl = java.time.Duration.between(LocalDateTime.now(), activity.getEndTime());
        if (ttl.isZero() || ttl.isNegative()) {
            ttl = java.time.Duration.ofMinutes(1);   // 已结束的活活动：给个短 TTL，别写个永久 key
        }

        redis.opsForValue().set(RedisKeyConstants.seckillStock(activityId),
                String.valueOf(activity.getRemain()), ttl);
        log.info("秒杀库存已预热: activityId={}, stock={}, ttl={}分钟",
                activityId, activity.getRemain(), ttl.toMinutes());
        return true;
    }

    /**
     * 每 30 秒扫描一次活动表：到点开始（先预热再翻状态）→ 进行中；到点结束 → 已结束。
     *
     * <p>★三个设计点，都是以前缺失的：</p>
     * <ol>
     *   <li><b>先预热、后翻状态</b>。反过来会出现一个空窗：状态已经是"进行中"、
     *       但 Redis 里还没有库存，此时用户来抢就是"活动已开始却提示已抢完"。</li>
     *   <li><b>翻状态用 CAS（{@code WHERE status = 0}）而不是 updateById</b>。
     *       {@code @Scheduled} 在多实例部署下每台都会跑一遍，没有 CAS 就是两实例同时发放，
     *       而"重复发放"这件事在有余量扣减时是会真出问题的。CAS 返回 0 行就说明
     *       这个活动已经被处理过，直接跳过——<b>不需要引分布式锁</b>，
     *       这也是本项目"并发防御三档"里必须防这一档的最省事解法。</li>
     *   <li>查询条件是 {@code start_time <= now}，所以"开始时间已经过了但没发放"的活动
     *       （配晚了、服务当时没起来）会被<b>下一轮扫描直接补发</b>，不会永久卡在待开始。
     *       这正是讨论里担心的"错过时点"问题的兜底：偏差不影响正确性，只影响几秒体验。</li>
     * </ol>
     */
    @Scheduled(fixedDelay = 30_000)
    public void scanAndSwitch() {
        LocalDateTime now = LocalDateTime.now();

        // ===== 到点（或已过点）的开始：先预热库存，再 CAS 翻状态 =====
        List<SeckillActivity> toStart = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 0)
                        .le(SeckillActivity::getStartTime, now));
        for (SeckillActivity activity : toStart) {
            if (!preheat(activity.getId())) {
                continue;
            }
            // 只有把状态从 0 改成 1 的那一次才算"发放成功"
            if (activityMapper.markRunning(activity.getId(), now) > 0) {
                log.info("秒杀活动已发放: id={}, courseId={}, 原定开始时间={}",
                        activity.getId(), activity.getCourseId(), activity.getStartTime());
            }
        }

        // ===== 到点结束：CAS 翻状态。Redis key 留给 TTL 自然过期 =====
        List<SeckillActivity> toEnd = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 1)
                        .le(SeckillActivity::getEndTime, now));
        for (SeckillActivity activity : toEnd) {
            if (activityMapper.markEnded(activity.getId()) > 0) {
                log.info("秒杀活动已结束: id={}", activity.getId());
            }
        }
    }
}
