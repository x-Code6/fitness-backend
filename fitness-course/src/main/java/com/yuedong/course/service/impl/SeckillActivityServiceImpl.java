package com.yuedong.course.service.impl;

import com.yuedong.common.constant.RedisKeyConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.course.api.dto.SeckillAdminDTO;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import com.yuedong.course.api.dto.SeckillSaveDTO;
import com.yuedong.course.mapper.CourseMapper;
import com.yuedong.course.mapper.SeckillActivityMapper;
import com.yuedong.course.model.entity.Course;
import com.yuedong.course.model.entity.SeckillActivity;
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
        return SeckillActiveDTO.builder()
                .activityId(a.getId())
                .courseId(a.getCourseId())
                .seckillPrice(a.getSeckillPrice())
                .endTime(a.getEndTime() == null ? null
                        : a.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                .build();
    }

    @Override
    public boolean deductRemain(Long activityId) {
        return activityMapper.deductRemain(activityId) > 0;
    }

    @Override
    public boolean preheat(Long activityId) {
        SeckillActivity a = activityMapper.selectById(activityId);
        if (a == null) {
            return false;
        }
        // 库存写 Redis（扣减起点）+ 清空"一人一单"资格集合（新一轮活动）
        redis.opsForValue().set(RedisKeyConstants.seckillStock(activityId), String.valueOf(a.getRemain()),
                Duration.between(LocalDateTime.now(), a.getEndTime()).abs());
        redis.delete(RedisKeyConstants.seckillBought(activityId));
        log.info("秒杀库存已预热: activityId={}, stock={}", activityId, a.getRemain());
        return true;
    }

    // =====================================================================
    // 管理端
    // =====================================================================
    @Override
    public List<SeckillAdminDTO> adminList() {
        Map<Long, Course> courseMap = courseMapper.selectList(null).stream()
                .collect(Collectors.toMap(Course::getId, Function.identity()));
        return activityMapper.selectList(new LambdaQueryWrapper<SeckillActivity>()
                        .orderByDesc(SeckillActivity::getId))
                .stream()
                .map(a -> SeckillAdminDTO.builder()
                        .id(a.getId())
                        .courseId(a.getCourseId())
                        .courseName(courseMap.get(a.getCourseId()) != null
                                ? courseMap.get(a.getCourseId()).getName() : "已删除课程")
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
        SeckillActivity a = new SeckillActivity();
        a.setCourseId(dto.getCourseId());
        a.setSeckillPrice(dto.getSeckillPrice());
        a.setTotal(dto.getTotal());
        a.setRemain(dto.getTotal());
        a.setStartTime(LocalDateTime.parse(dto.getStartTime().replace(' ', 'T')));
        a.setEndTime(LocalDateTime.parse(dto.getEndTime().replace(' ', 'T')));
        a.setStatus(0); // 待开始：定时任务到点预热库存并置为进行中
        activityMapper.insert(a);
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
     * 每 30 秒扫描一次活动表：到点开始（预热库存）→ 进行中；到点结束 → 已结束。
     * 也可以用"延迟消息"在开始时间点精准触发；扫表实现更简单，量小够用。
     */
    @Scheduled(fixedDelay = 30_000)
    public void scanAndSwitch() {
        LocalDateTime now = LocalDateTime.now();

        // 到点开始：预热库存
        List<SeckillActivity> toStart = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 0)
                        .le(SeckillActivity::getStartTime, now));
        toStart.forEach(a -> {
            a.setStatus(1);
            activityMapper.updateById(a);
            preheat(a.getId());
            log.info("秒杀活动开始: id={}, course={}", a.getId(), a.getCourseId());
        });

        // 到点结束：状态流转（Redis key 留给 TTL 自然过期）
        List<SeckillActivity> toEnd = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, 1)
                        .le(SeckillActivity::getEndTime, now));
        toEnd.forEach(a -> {
            a.setStatus(2);
            activityMapper.updateById(a);
            log.info("秒杀活动结束: id={}", a.getId());
        });
    }
}
