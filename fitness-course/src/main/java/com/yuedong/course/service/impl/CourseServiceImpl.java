package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.ScheduleDTO;
import com.yuedong.course.cache.CacheWrapper;
import com.yuedong.course.mapper.CourseMapper;
import com.yuedong.course.mapper.ScheduleMapper;
import com.yuedong.course.model.entity.Course;
import com.yuedong.course.model.entity.Schedule;
import com.yuedong.course.model.entity.SeckillActivity;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.course.model.vo.ScheduleVO;
import com.yuedong.course.service.CourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 课程业务实现 —— 本服务的核心是 getDetail() 的"缓存三件套"。
 *
 * <p>三件套对应关系：</p>
 * <ul>
 *   <li>穿透（查不存在的 id 打穿到 DB）→ <b>空值缓存</b>：查不到也把"空"缓存 2 分钟；</li>
 *   <li>击穿（热 key 过期瞬间并发打到 DB）→ <b>逻辑过期 + 互斥重建</b>：
 *       缓存不设物理 TTL，内嵌逻辑过期时间；过期后拿 Redisson 锁的线程异步重建，
 *       所有请求立即返回旧数据，全程无人阻塞；</li>
 *   <li>雪崩（大量 key 同一时刻过期）→ <b>随机 TTL</b>：基础 30 分钟 + 随机 0~5 分钟。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    private final CourseMapper courseMapper;
    private final ScheduleMapper scheduleMapper;
    private final com.yuedong.course.mapper.SeckillActivityMapper seckillActivityMapper;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    /** 缓存重建线程池（配置见 CacheRebuildExecutorConfig） */
    @Qualifier("cacheRebuildExecutor")
    private final ExecutorService rebuildExecutor;

    /** 逻辑过期基础时长 */
    private static final Duration BASE_TTL = Duration.ofMinutes(30);

    // =====================================================================
    // 课程列表（低频筛选组合，直接查库；命中并发度高的详情才上重缓存）
    // =====================================================================
    @Override
    public List<CourseVO> list(String type, String keyword) {
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<Course>()
                .eq(Course::getStatus, 1)
                .eq(type != null && !type.isBlank() && !"全部".equals(type), Course::getType, type)
                .like(keyword != null && !keyword.isBlank(), Course::getName, keyword);
        if (keyword != null && !keyword.isBlank()) {
            // 关键词同时匹配课程名/教练/标签：用 or 拼接 like
            wrapper.and(w -> w.like(Course::getName, keyword)
                    .or().like(Course::getCoach, keyword)
                    .or().like(Course::getTags, keyword));
        }
        List<Course> courses = courseMapper.selectList(wrapper);

        // 进行中的秒杀活动一次性查出，内存组装（避免 N+1 查询）
        Map<Long, SeckillActivity> activityMap = activeActivityMap();
        return courses.stream()
                .map(c -> toVO(c, activityMap.get(c.getId())))
                .toList();
    }

    // =====================================================================
    // 课程详情：缓存三件套
    // =====================================================================
    @Override
    public CourseVO getDetail(Long id) {
        String detailKey = RedisKeyConstants.courseDetail(id);

        // ===== 第 1 步：查逻辑过期缓存 =====
        String cached = redis.opsForValue().get(detailKey);
        if (cached != null) {
            CacheWrapper<CourseVO> wrapper = parseWrapper(cached);

            // ---- 1.1 逻辑未过期：直接返回（绝大多数请求走这里，零 DB 压力） ----
            if (wrapper.getExpireTime() > System.currentTimeMillis()) {
                return wrapper.getData();
            }

            // ---- 1.2 逻辑过期：尝试拿互斥锁去异步重建 ----
            RLock lock = redisson.getLock(RedisKeyConstants.courseDetail(id));
            if (lock.tryLock()) {
                try {
                    // 双重检查：排队期间可能已被上一个持锁线程重建好
                    cached = redis.opsForValue().get(detailKey);
                    wrapper = parseWrapper(cached);
                    if (wrapper != null && wrapper.getExpireTime() > System.currentTimeMillis()) {
                        return wrapper.getData();
                    }
                    // 把"查库 + 重建缓存"丢进线程池异步执行，本请求不等待
                    rebuildExecutor.submit(() -> rebuildCache(id));
                    log.info("课程详情逻辑过期，已提交异步重建: id={}", id);
                } finally {
                    lock.unlock();
                }
            }
            // 没抢到锁的线程直接返回旧数据——宁可旧几秒，也不打到 DB（防击穿核心）
            return wrapper != null ? wrapper.getData() : null;
        }

        // ===== 第 2 步：缓存未命中 → 查空值缓存（防穿透：恶意查不存在的 id） =====
        if (Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.courseEmpty(id)))) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }

        // ===== 第 3 步：查数据库 =====
        Course course = courseMapper.selectById(id);
        if (course == null) {
            // 查不到也要缓存"空值"，让同一个不存在 id 的反复请求在 Redis 被挡住
            redis.opsForValue().set(RedisKeyConstants.courseEmpty(id), "1", Duration.ofMinutes(2));
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }

        // ===== 第 4 步：写逻辑过期缓存（TTL 30min + 随机 0~5min 防雪崩）并返回 =====
        CourseVO vo = toVO(course, activeActivityMap().get(id));
        writeCache(vo);
        return vo;
    }

    /**
     * 异步重建：查最新库数据回填缓存；课程已下架/删除则清缓存 + 记空值。
     */
    private void rebuildCache(Long id) {
        try {
            Course course = courseMapper.selectById(id);
            if (course == null || course.getStatus() != 1) {
                redis.delete(RedisKeyConstants.courseDetail(id));
                redis.opsForValue().set(RedisKeyConstants.courseEmpty(id), "1", Duration.ofMinutes(2));
                return;
            }
            writeCache(toVO(course, activeActivityMap().get(id)));
            log.info("课程详情缓存重建完成: id={}", id);
        } catch (Exception e) {
            // 重建失败不影响主流程：下一个逻辑过期请求会再次触发
            log.error("课程详情缓存重建失败: id={}", id, e);
        }
    }

    /** 组装并写入逻辑过期缓存 */
    private void writeCache(CourseVO vo) {
        long expireAt = System.currentTimeMillis() + BASE_TTL.toMillis()
                + ThreadLocalRandomHolder.randomMinutes(5);
        try {
            redis.opsForValue().set(RedisKeyConstants.courseDetail(vo.getId()),
                    objectMapper.writeValueAsString(new CacheWrapper<>(vo, expireAt)));
        } catch (Exception e) {
            log.error("课程详情缓存写入失败: id={}", vo.getId(), e);
        }
    }

    private CacheWrapper<CourseVO> parseWrapper(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructParametricType(CacheWrapper.class, CourseVO.class));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "缓存数据解析失败");
        }
    }

    // =====================================================================
    // 场次：课程详情页"选择上课时间"
    // =====================================================================
    @Override
    public List<ScheduleVO> scheduleList(Long courseId) {
        // 只返回"现在之后"的可约场次，按时间升序
        return scheduleMapper.selectList(new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getCourseId, courseId)
                        .eq(Schedule::getStatus, 1)
                        .gt(Schedule::getScheduleTime, java.time.LocalDateTime.now())
                        .orderByAsc(Schedule::getScheduleTime))
                .stream()
                .map(s -> ScheduleVO.builder()
                        .id(s.getId())
                        .scheduleTime(s.getScheduleTime())
                        .capacity(s.getCapacity())
                        .booked(s.getBooked())
                        .full(s.getBooked() >= s.getCapacity())
                        .build())
                .toList();
    }

    @Override
    public ScheduleDTO getScheduleInner(Long scheduleId) {
        Schedule s = scheduleMapper.selectById(scheduleId);
        if (s == null || s.getStatus() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "场次不存在或已取消");
        }
        return ScheduleDTO.builder()
                .id(s.getId())
                .courseId(s.getCourseId())
                .scheduleTime(s.getScheduleTime())
                .capacity(s.getCapacity())
                .booked(s.getBooked())
                .status(s.getStatus())
                .build();
    }

    @Override
    public boolean occupySchedule(Long scheduleId) {
        // 原子 UPDATE：本场 booked < capacity 才 +1，并发下不会超约
        return scheduleMapper.occupy(scheduleId) > 0;
    }

    @Override
    public boolean releaseSchedule(Long scheduleId) {
        return scheduleMapper.release(scheduleId) > 0;
    }

    // =====================================================================
    // 内部接口实现
    // =====================================================================
    @Override
    public CourseDTO getInnerDetail(Long id) {
        Course course = courseMapper.selectById(id);
        if (course == null || course.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        return CourseDTO.builder()
                .id(course.getId())
                .name(course.getName())
                .coach(course.getCoach())
                .storeName(course.getStoreName())
                .price(course.getPrice())
                .nextTime(course.getNextTime())
                .capacity(course.getCapacity())
                .booked(course.getBooked())
                .build();
    }

    @Override
    public boolean occupy(Long courseId) {
        // 原子 UPDATE：WHERE booked < capacity 保证并发下不超约
        return courseMapper.occupy(courseId) > 0;
    }

    @Override
    public boolean release(Long courseId) {
        return courseMapper.release(courseId) > 0;
    }

    // =====================================================================
    // 管理端（fitness-admin-server Feign 调用）；编辑后主动失效缓存
    // =====================================================================
    @Override
    public PageDTO<CourseAdminDTO> adminPage(String type, String keyword, Integer page, Integer size) {
        var result = courseMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        page == null ? 1 : page, size == null ? 10 : size),
                new LambdaQueryWrapper<Course>()
                        .eq(type != null && !type.isBlank() && !"全部".equals(type), Course::getType, type)
                        .like(keyword != null && !keyword.isBlank(), Course::getName, keyword)
                        .orderByDesc(Course::getId));
        List<CourseAdminDTO> rows = result.getRecords().stream().map(this::toAdminDTO).toList();
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public void adminSave(CourseSaveDTO dto) {
        Course course;
        if (dto.getId() != null) {
            course = courseMapper.selectById(dto.getId());
            if (course == null) throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        } else {
            course = new Course();
            course.setBooked(0);
            course.setStatus(1);
        }
        course.setName(dto.getName());
        course.setType(dto.getType());
        course.setCoach(dto.getCoach());
        course.setCoachTitle(dto.getCoachTitle());
        course.setDuration(dto.getDuration());
        course.setCalories(dto.getCalories());
        course.setCapacity(dto.getCapacity());
        course.setPrice(dto.getPrice());
        course.setTags(dto.getTags());
        course.setStoreName(dto.getStoreName());
        course.setNextTime(dto.getNextTime() == null ? "预约制" : dto.getNextTime());
        course.setDescription(dto.getDescription());
        course.setSuitable(dto.getSuitable());
        course.setUpdatedAt(java.time.LocalDateTime.now());
        if (dto.getId() != null) {
            courseMapper.updateById(course);
        } else {
            courseMapper.insert(course);
        }
        // 缓存一致性：管理端改数据后主动删详情缓存，下次访问回源最新数据
        redis.delete(RedisKeyConstants.courseDetail(course.getId()));
        redis.delete(RedisKeyConstants.courseEmpty(course.getId()));
    }

    @Override
    public void adminStatus(Long id, Integer status) {
        Course patch = new Course();
        patch.setId(id);
        patch.setStatus(status);
        patch.setUpdatedAt(java.time.LocalDateTime.now());
        courseMapper.updateById(patch);
        redis.delete(RedisKeyConstants.courseDetail(id)); // 下架立即对用户端生效
    }

    @Override
    public void adminDelete(Long id) {
        courseMapper.deleteById(id);
        redis.delete(RedisKeyConstants.courseDetail(id));
        redis.delete(RedisKeyConstants.courseEmpty(id));
    }

    @Override
    public List<ScheduleDTO> scheduleAdminList(Long courseId) {
        // 管理端看全量（含历史），按时间升序
        return scheduleMapper.selectList(new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getCourseId, courseId)
                        .orderByAsc(Schedule::getScheduleTime))
                .stream()
                .map(s -> ScheduleDTO.builder()
                        .id(s.getId())
                        .courseId(s.getCourseId())
                        .scheduleTime(s.getScheduleTime())
                        .capacity(s.getCapacity())
                        .booked(s.getBooked())
                        .status(s.getStatus())
                        .build())
                .toList();
    }

    @Override
    public void scheduleAdminSave(Long courseId, String scheduleTime, Integer capacity) {
        Schedule s = new Schedule();
        s.setCourseId(courseId);
        s.setScheduleTime(java.time.LocalDateTime.parse(scheduleTime.replace(' ', 'T')));
        s.setCapacity(capacity);
        s.setBooked(0);
        s.setStatus(1);
        scheduleMapper.insert(s);
    }

    @Override
    public void scheduleAdminDelete(Long scheduleId) {
        scheduleMapper.deleteById(scheduleId);
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 进行中的活动 → courseId 映射 */
    private Map<Long, SeckillActivity> activeActivityMap() {
        return seckillActivityMapper.selectList(
                        new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getStatus, 1))
                .stream()
                .collect(Collectors.toMap(SeckillActivity::getCourseId, Function.identity()));
    }

    /** 实体 → 管理端行 DTO（列表展示 + 编辑回填） */
    private CourseAdminDTO toAdminDTO(Course course) {
        return CourseAdminDTO.builder()
                .id(course.getId())
                .name(course.getName())
                .type(course.getType())
                .coach(course.getCoach())
                .coachTitle(course.getCoachTitle())
                .duration(course.getDuration())
                .calories(course.getCalories())
                .capacity(course.getCapacity())
                .booked(course.getBooked())
                .price(course.getPrice())
                .tags(course.getTags())
                .storeName(course.getStoreName())
                .nextTime(course.getNextTime())
                .description(course.getDescription())
                .suitable(course.getSuitable())
                .status(course.getStatus())
                .build();
    }

    /** 实体 + 活动 → VO */
    private CourseVO toVO(Course course, SeckillActivity activity) {
        CourseVO.CourseVOBuilder builder = CourseVO.builder()
                .id(course.getId())
                .name(course.getName())
                .type(course.getType())
                .coach(course.getCoach())
                .coachTitle(course.getCoachTitle())
                .duration(course.getDuration())
                .calories(course.getCalories())
                .capacity(course.getCapacity())
                .booked(course.getBooked())
                .price(course.getPrice())
                .tags(course.getTags() == null ? List.of() : List.of(course.getTags().split(",")))
                .storeName(course.getStoreName())
                .nextTime(course.getNextTime())
                .description(course.getDescription())
                .suitable(course.getSuitable());
        if (activity != null && activity.getEndTime() != null) {
            builder.seckill(CourseVO.SeckillInfo.builder()
                    .activityId(activity.getId())
                    .price(activity.getSeckillPrice())
                    .total(activity.getTotal())
                    .remain(activity.getRemain())
                    .endTime(activity.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    .build());
        }
        return builder.build();
    }

    /** 占位工具：随机分钟数（防缓存同时过期） */
    static final class ThreadLocalRandomHolder {
        static long randomMinutes(int bound) {
            return java.util.concurrent.ThreadLocalRandom.current().nextLong(bound) * 60 * 1000;
        }
    }
}
