package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.AdminCountDTO;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.CourseStoreKeyDTO;
import com.yuedong.course.api.dto.CourseWindowAdminDTO;
import com.yuedong.course.api.dto.CourseWindowSaveDTO;
import com.yuedong.course.cache.CacheWrapper;
import com.yuedong.course.mapper.CoachMapper;
import com.yuedong.course.mapper.CourseCommentMapper;
import com.yuedong.course.mapper.CourseMapper;
import com.yuedong.course.mapper.CourseStoreMapper;
import com.yuedong.course.mapper.SeckillActivityMapper;
import com.yuedong.course.mapper.StoreMapper;
import com.yuedong.course.model.entity.Coach;
import com.yuedong.course.model.entity.CoachCourse;
import com.yuedong.course.model.entity.Course;
import com.yuedong.course.model.entity.CourseComment;
import com.yuedong.course.model.entity.CourseStore;
import com.yuedong.course.model.entity.SeckillActivity;
import com.yuedong.course.model.entity.Store;
import com.yuedong.course.model.vo.CourseDetailContextVO;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.course.mapper.CoachCourseMapper;
import com.yuedong.course.rule.RuleContext;
import com.yuedong.course.rule.RuleDescribeFactory;
import com.yuedong.course.service.BannerService;
import com.yuedong.course.service.CourseService;
import com.yuedong.order.api.client.RatingClient;
import com.yuedong.order.api.dto.RatingStatsDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 课程业务实现（v2）。
 *
 * <p>读本类的三个入口：</p>
 * <ol>
 *   <li><b>缓存三件套</b>（{@link #getDetail}）：防击穿=逻辑过期 + 互斥锁 + 异步重建，
 *       防穿透=空值缓存，防雪崩=物理 TTL + 随机抖动。
 *       旧实现漏了第三件：{@code writeCache} 用的是不带 TTL 的 set，key 只增不减；</li>
 *   <li><b>两级缓存</b>：基础课程数据缓存 30 分钟；评分、门店列表、教练数、门店规则
 *       这些允许短期不一致的数据按 {@code (courseId, storeId)} 再缓存 60 秒。
 *       门店维度键空间会变大，但 60 秒 TTL 会很快回收冷键，热点详情的 DB/Feign 压力明显下降；</li>
 *   <li><b>可售门店来自 {@code t_course_store}</b>：旧库把 store_id/store_name 写死在课程表，
 *       一门课只能一家店。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    /** 逻辑过期基础时长：写进缓存值里的"业务过期时间" */
    private static final Duration BASE_TTL = Duration.ofMinutes(30);

    /**
     * 物理 TTL 相对逻辑 TTL 的倍数。
     *
     * <p>★为什么物理 TTL 要比逻辑 TTL <b>长</b>而不是相等：逻辑过期先触发，请求返回旧数据并异步重建，
     * 一切正常；物理过期才让 key 真正消失，那一刻才有回源打库的风险。取 2 倍等于
     * "逻辑过期负责刷新，物理过期只负责兜底回收"——既不永久堆积 key，
     * 也不会让物理过期抢先把请求打到 DB。</p>
     */
    private static final int PHYSICAL_TTL_FACTOR = 2;

    /** 物理 TTL 的随机抖动上限（分钟）：防大量 key 同一时刻集体过期，即防雪崩 */
    private static final int JITTER_MAX_MINUTES = 5;

    /** 空值缓存存活时间：够挡住反复刷不存在的 id，又不至于让新建课程迟迟不可见 */
    private static final Duration EMPTY_TTL = Duration.ofMinutes(2);

    /**
     * 门店级上下文缓存时长。
     * <p>★60 秒是“允许短期不一致”的落地：管理端改课程/门店/教练、order 侧新增评价后，
     * 详情最坏旧 60 秒；换掉的是每次详情 1 次 Feign + 最多 4 次 DB。更新不主动删缓存。</p>
     */
    private static final Duration CONTEXT_TTL = Duration.ofSeconds(60);

    /** 互斥锁的持有时长（秒）。不带 leaseTime 的话要靠 Redisson 看门狗续期，进程被 kill -9 时释放很慢 */
    private static final long LOCK_LEASE_SECONDS = 10;

    /** 课程分类里表示"不加筛选"的前端约定值 */
    private static final String TYPE_ALL = "全部";

    private final CourseMapper courseMapper;
    private final CourseStoreMapper courseStoreMapper;
    /** 只为详情那一格"共 N 条评论"计数，评论的写路径在 CourseCommentServiceImpl */
    private final CourseCommentMapper commentMapper;
    private final CoachMapper coachMapper;
    /** 只为"这门课有几位教练会教"这一个聚合，不写教练的任何状态 */
    private final CoachCourseMapper coachCourseMapper;
    private final StoreMapper storeMapper;
    private final SeckillActivityMapper seckillActivityMapper;
    /** 活动页联动：课程保存/下架/删除时同步 Banner */
    private final BannerService bannerService;
    /** 规则文案工厂（D21 策略模式）。文案只在这一处产出，列表/详情/时段页都从这里取 */
    private final RuleDescribeFactory ruleDescribeFactory;
    /** 评分统计来自 order 域（评价明细与汇总都在 yd_order）。只读，取不到就显示"暂无评分" */
    private final RatingClient ratingClient;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    /** 缓存重建线程池（配置见 CacheRebuildExecutorConfig：队列 64 + AbortPolicy） */
    @Qualifier("cacheRebuildExecutor")
    private final ExecutorService rebuildExecutor;

    /**
     * 本实例内正在重建的课程 id。
     * <p>★用 {@code ConcurrentHashMap.newKeySet()} 做单飞：同门课在本实例最多一个重建任务进池，
     * 避免热点课程在逻辑过期瞬间把队列塞满、把其它课程的重建任务挤掉。
     * 跨实例仍由异步任务里的 Redisson 锁兜底。</p>
     */
    private final Set<Long> rebuildingCourses = ConcurrentHashMap.newKeySet();

    // =====================================================================
    // 课程列表：不缓存（筛选组合多、命中率低），只修搜索条件 + 补门店数统计
    // =====================================================================
    @Override
    public List<CourseVO> list(String type, String keyword, String kind) {
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<Course>()
                // 只查上架课程：WHERE status = 1，命中 idx_kind_status 的前缀
                .eq(Course::getStatus, 1)
                .eq(type != null && !type.isBlank() && !TYPE_ALL.equals(type), Course::getType, type)
                // kind 为空表示 SALE 与 CONTENT 都要（首页两块混排）
                .eq(kind != null && !kind.isBlank(), Course::getCourseKind, kind);

        // ★修掉一个旧 bug：以前先无条件加了 like(name)，后面又加了 "(name or coach or tags)"，
        //   两个条件是 AND 关系，等于"按教练或标签搜索"永远搜不到东西。
        //   正确写法是只加一次这个 or 组合；教练名已不在课程表，这里改成匹配 名称/标签/分类
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            wrapper.and(w -> w.like(Course::getName, trimmed)
                    .or().like(Course::getTags, trimmed)
                    .or().like(Course::getType, trimmed));
        }

        List<Course> courses = courseMapper.selectList(wrapper);
        if (courses.isEmpty()) {
            return List.of();
        }

        // 进行中的秒杀活动一次查出，内存组装（避免每门课查一次活动的 N+1）
        Map<Long, SeckillActivity> activityMap = activeActivityMap();

        // 可售门店数：一次查回这几门课的 t_course_store 行，内存按 courseId 计数
        // ★不写 GROUP BY 动态 SQL，也不在循环里查库：十几门课对应几十行关联记录，
        //   一次取回内存分组最简单，可读性也最好
        Map<Long, Integer> storeCountMap = loadStoreCounts(
                courses.stream().map(Course::getId).toList());

        List<CourseVO> result = new ArrayList<>();
        for (Course course : courses) {
            result.add(toVO(course, activityMap.get(course.getId()),
                    storeCountMap.getOrDefault(course.getId(), 0)));
        }
        // 评分不进上面的组装、单独一次批量取：见 fillCourseRatings 的注释
        fillCourseRatings(result);
        return result;
    }

    // =====================================================================
    // 课程详情：缓存三件套
    // =====================================================================
    @Override
    public CourseVO getDetail(Long id, Long storeId) {
        CacheWrapper<CourseVO> cached = readCache(id);

        if (cached != null) {
            // ---- 情况 A：逻辑未过期，直接返回（绝大多数请求到这里，零 DB 压力）----
            if (cached.getExpireTime() > System.currentTimeMillis()) {
                return withStoreContext(cached.getData(), storeId);
            }

            // ---- 情况 B：逻辑已过期。抢到锁的人提交异步重建，其余人照旧返回旧数据 ----
            // 这就是"防击穿"：宁可给用户看几秒前的旧数据，也不让一堆请求同时打 DB
            tryRebuildAsync(id);
            return withStoreContext(cached.getData(), storeId);
        }

        // ---- 情况 C：缓存里什么都没有。先看空值标记（防穿透）----
        if (Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.courseEmpty(id)))) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }

        // 查课程主体：WHERE id=?，命中主键
        Course course = courseMapper.selectById(id);
        if (course == null || course.getStatus() != 1) {
            // 查不到也要缓存"空值"，让同一个不存在 id 的后续请求在 Redis 就被挡住
            redis.opsForValue().set(RedisKeyConstants.courseEmpty(id), "1", EMPTY_TTL);
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }

        CourseVO vo = toVO(course, activeActivityMap().get(id),
                loadStoreCounts(List.of(id)).getOrDefault(id, 0));
        writeCache(vo);
        return withStoreContext(vo, storeId);
    }

    /**
     * 读缓存并反序列化。
     *
     * <p>★返回 null 表示"按未命中处理"，三种情况都会走到这里：键不存在、JSON 解析失败、
     * 内容结构不对。旧实现在解析失败时直接抛 {@code SYSTEM_ERROR}，
     * 等于"缓存里坏了一条数据 → 用户看详情就报系统错误"，
     * 而缓存本来只是加速手段，坏掉时应该退回查库，而不是把故障放大给用户。</p>
     */
    private CacheWrapper<CourseVO> readCache(Long id) {
        String json = redis.opsForValue().get(RedisKeyConstants.courseDetail(id));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructParametricType(CacheWrapper.class, CourseVO.class));
        } catch (Exception e) {
            log.warn("课程详情缓存内容异常，按未命中处理: id={}", id, e);
            // 顺手把这个坏键删掉，否则每次请求都要重复解析失败一次
            redis.delete(RedisKeyConstants.courseDetail(id));
            return null;
        }
    }

    /**
     * 逻辑过期后尝试触发异步重建：用互斥锁保证同一课程只有一个线程去重建。
     *
     * <p>四个细节都写在代码里：</p>
     * <ul>
     *   <li>本实例用 {@link #rebuildingCourses} 单飞：同一门课最多一个任务进池，
     *       热点课程不会在逻辑过期瞬间把队列塞满；</li>
     *   <li>锁用<b>独立 key</b>（{@code course:lock:detail:}）而不是复用数据 key——旧实现拿数据 key 加锁，
     *       锁自身的内部结构与缓存 JSON 挤在同一个键上；</li>
     *   <li>{@code tryLock(0, ...)}：等 0 秒，抢不到立刻走人（去返回旧数据），
     *       不排队——排队就把"异步重建不阻塞用户"这个优点抵消了；</li>
     *   <li>带 leaseTime，避免进程被强杀后锁迟迟不放。</li>
     * </ul>
     */
    private void tryRebuildAsync(Long id) {
        // ★本实例单飞：同一门课最多一个重建任务；跨实例仍由 Redisson 锁兜底
        if (!rebuildingCourses.add(id)) {
            return;
        }
        try {
            // ★锁必须在异步任务里“拿到 → 重建完 → 释放”，不能在请求线程里 submit 完就解锁：
            //   提前解锁会让每个逻辑过期请求都再提交一次重建任务，重复打 DB。
            rebuildExecutor.submit(() -> {
                try {
                    RLock lock = redisson.getLock(RedisKeyConstants.COURSE_LOCK + id);
                    boolean locked = false;
                    try {
                        locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
                        if (!locked) {
                            return;
                        }
                        // 双重检查：等锁期间可能已被上一个持锁线程重建完
                        CacheWrapper<CourseVO> latest = readCache(id);
                        if (latest != null && latest.getExpireTime() > System.currentTimeMillis()) {
                            return;
                        }
                        rebuildCache(id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();   // 恢复中断标记，不要吞掉
                        log.warn("等待课程缓存锁被中断: id={}", id);
                    } catch (Exception e) {
                        log.error("课程详情异步重建失败: id={}", id, e);
                    } finally {
                        // 只解自己拿到的锁；isHeldByCurrentThread 防止解掉别人的锁
                        if (locked && lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } finally {
                    // 任务结束（正常/异常/没抢到锁）都释放本实例标记
                    rebuildingCourses.remove(id);
                }
            });
            log.info("课程详情逻辑过期，已提交异步重建: id={}", id);
        } catch (Exception e) {
            // 线程池拒绝/已关闭：必须清标记，否则这门课在本实例永久不再重建
            rebuildingCourses.remove(id);
            log.error("提交课程详情异步重建失败: id={}", id, e);
        }
    }

    /** 异步重建：查最新数据回填缓存；课程已下架或不存在则清缓存并写空值标记 */
    private void rebuildCache(Long id) {
        try {
            Course course = courseMapper.selectById(id);
            if (course == null || course.getStatus() != 1) {
                redis.delete(RedisKeyConstants.courseDetail(id));
                redis.opsForValue().set(RedisKeyConstants.courseEmpty(id), "1", EMPTY_TTL);
                return;
            }
            // ★重建期间管理端可能刚改了课程/门店并主动删了缓存：
            //   这时本次查到的可能是旧快照，写回去会让旧配置再活 30 分钟。发现 key 已被删就丢弃。
            //   （仍有“检查后、写入前刚好被删”的极窄窗口；彻底解决要加版本号，本期先把这个大窗口关掉。）
            boolean detailStillCached = Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.courseDetail(id)));
            boolean emptyStillCached = Boolean.TRUE.equals(redis.hasKey(RedisKeyConstants.courseEmpty(id)));
            if (!detailStillCached && !emptyStillCached) {
                log.info("课程详情缓存重建期间被主动失效，丢弃本次结果: id={}", id);
                return;
            }
            writeCache(toVO(course, activeActivityMap().get(id),
                    loadStoreCounts(List.of(id)).getOrDefault(id, 0)));
            log.info("课程详情缓存重建完成: id={}", id);
        } catch (Exception e) {
            // 重建失败不影响主流程：下一个逻辑过期的请求会再次触发
            log.error("课程详情缓存重建失败: id={}", id, e);
        }
    }

    /** 写缓存：值里带逻辑过期时间戳，键上带"物理 TTL × 倍数 + 随机抖动" */
    private void writeCache(CourseVO vo) {
        long jitterMillis = ThreadLocalRandom.current().nextLong(JITTER_MAX_MINUTES) * 60_000;
        long logicalExpireAt = System.currentTimeMillis() + BASE_TTL.toMillis() + jitterMillis;
        Duration physicalTtl = BASE_TTL.multipliedBy(PHYSICAL_TTL_FACTOR).plusMillis(jitterMillis);
        try {
            redis.opsForValue().set(RedisKeyConstants.courseDetail(vo.getId()),
                    objectMapper.writeValueAsString(new CacheWrapper<>(vo, logicalExpireAt)),
                    physicalTtl);
        } catch (Exception e) {
            // 缓存写失败不能让整个请求失败：大不了这一次回源查库
            log.error("课程详情缓存写入失败: id={}", vo.getId(), e);
        }
    }

    /**
     * 给缓存取出的 VO 补上门店维度的实时字段。
     *
     * <p>★两级缓存：基础 VO 缓存 30 分钟；这里补的评分/门店列表/教练数/门店规则
     * 按 {@code courseId + storeId} 再缓存 60 秒。更新不主动删，靠 TTL 收敛。</p>
     *
     * <p>缓存里的对象是每次反序列化出来的新对象，apply 不会污染 Redis 里的基础缓存。</p>
     */
    private CourseVO withStoreContext(CourseVO vo, Long storeId) {
        if (vo == null) {
            return vo;
        }

        // ===== 第 1 步：先看 60 秒门店上下文缓存 =====
        CourseDetailContextVO cachedContext = readContextCache(vo.getId(), storeId);
        if (cachedContext != null) {
            applyContext(vo, cachedContext);
            return vo;
        }

        // ===== 第 2 步：未命中，实时拼装并回写上下文缓存 =====
        // 评分在 30 分钟基础缓存之外：新评价要尽快可见，放基础缓存会"评了却看不见"
        fillCourseRatings(List.of(vo));

        // ★一次查询拿全门店关系：门店列表、目标门店规则、在售门店数都从同一份结果里取，
        //   不再额外 selectOne（原来同一请求内 t_course_store 查了两次）
        StoreContext storeContext = loadStoreContext(vo.getId());
        vo.setStores(storeContext.options());
        vo.setStoreCount(storeContext.activeStoreCount());
        vo.setLatestFreeText(storeContext.activeStoreCount() > 0
                ? storeContext.activeStoreCount() + " 家门店可约"
                : "暂无可约门店");

        // 评论数同理，而且只有展示课需要（可售课的口碑在 ratingText 那格）
        if ("CONTENT".equals(vo.getKind())) {
            Long comments = commentMapper.selectCount(new LambdaQueryWrapper<CourseComment>()
                    .eq(CourseComment::getCourseId, vo.getId())
                    .eq(CourseComment::getStatus, 1));
            vo.setCommentCount(comments == null ? 0 : comments.intValue());
        }

        if (storeId != null) {
            // 三表同库 JOIN 数教练：命中 t_coach_course.idx_course 与 t_coach.uk_store_name 前缀
            int coachCount = coachMapper.countByCourseAndStore(vo.getId(), storeId);
            vo.setCoachCount(coachCount);
            vo.setLatestFreeText(coachCount > 0 ? "今天 " + coachCount + " 位教练可约" : "暂无可约时段");

            // ★复用 loadStoreContext 里已经查出来的关系行，不再 courseStoreMapper.selectOne
            CourseStore relation = storeContext.relationByStore().get(storeId);
            RuleContext context = RuleContext.builder()
                    .courseKind(vo.getKind())
                    .saleType(vo.getSaleType())
                    .lessonHours(vo.getLessonHours())
                    .timesPerBuy(vo.getTimesPerBuy())
                    .validType(relation == null ? null : relation.getValidType())
                    .validDays(relation == null ? null : relation.getValidDays())
                    .useEndTime(relation == null ? null : relation.getUseEndTime())
                    .build();
            String scene = ruleDescribeFactory.courseSceneOf(vo.getKind(), vo.getSaleType());
            vo.setRuleTexts(ruleDescribeFactory.describe(scene, context));
            vo.setRuleSummary(ruleDescribeFactory.summary(scene, context));
        }

        writeContextCache(vo.getId(), storeId, extractContext(vo));
        return vo;
    }

    /**
     * 门店级上下文缓存（60 秒）。
     * <p>★更新不主动删，靠 TTL 收敛：管理端改课程/门店/教练、order 侧新增评价后，
     * 详情最坏旧 60 秒；换掉的是每次详情 1 次 Feign + 最多 4 次 DB。</p>
     */
    private CourseDetailContextVO readContextCache(Long courseId, Long storeId) {
        String key = RedisKeyConstants.courseDetailContext(courseId, storeId);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, CourseDetailContextVO.class);
        } catch (Exception e) {
            // 缓存坏了按未命中处理，不能把故障放大成详情 500
            log.warn("课程详情上下文缓存读取失败，按未命中处理: courseId={}, storeId={}",
                    courseId, storeId, e);
            try {
                redis.delete(key);
            } catch (Exception ignore) {
                // 删不掉就等 TTL
            }
            return null;
        }
    }

    private void writeContextCache(Long courseId, Long storeId, CourseDetailContextVO context) {
        try {
            redis.opsForValue().set(RedisKeyConstants.courseDetailContext(courseId, storeId),
                    objectMapper.writeValueAsString(context), CONTEXT_TTL);
        } catch (Exception e) {
            // 写失败不影响本次响应，下一次请求会再回源
            log.error("课程详情上下文缓存写入失败: courseId={}, storeId={}", courseId, storeId, e);
        }
    }

    /** 把 60 秒上下文叠回基础 VO（每次请求反序列化出的新对象，不会污染缓存） */
    private void applyContext(CourseVO vo, CourseDetailContextVO context) {
        vo.setRatingText(context.getRatingText());
        vo.setRatingCount(context.getRatingCount());
        vo.setStores(context.getStores());
        vo.setStoreCount(context.getStoreCount());
        vo.setCommentCount(context.getCommentCount());
        vo.setCoachCount(context.getCoachCount());
        vo.setLatestFreeText(context.getLatestFreeText());
        vo.setRuleTexts(context.getRuleTexts());
        vo.setRuleSummary(context.getRuleSummary());
    }

    /** 从刚拼好的 VO 里抽出需要缓存 60 秒的字段 */
    private CourseDetailContextVO extractContext(CourseVO vo) {
        return CourseDetailContextVO.builder()
                .ratingText(vo.getRatingText())
                .ratingCount(vo.getRatingCount())
                .stores(vo.getStores())
                .storeCount(vo.getStoreCount())
                .commentCount(vo.getCommentCount())
                .coachCount(vo.getCoachCount())
                .latestFreeText(vo.getLatestFreeText())
                .ruleTexts(vo.getRuleTexts())
                .ruleSummary(vo.getRuleSummary())
                .build();
    }

    // =====================================================================
    // 内部接口：给 order 服务建单时取课程快照
    // =====================================================================
    @Override
    public CourseDTO getInnerDetail(Long courseId, Long storeId) {
        // 查课程主体：WHERE id=?，命中主键；上下架状态在这里判，不给调用方留判空负担
        Course course = courseMapper.selectById(courseId);
        if (course == null || course.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }

        // 门店关系从 t_course_store 取（旧库的 store_id/store_name 已删）
        CourseStore relation = null;
        Store store = null;
        if (storeId != null) {
            relation = courseStoreMapper.selectOne(new LambdaQueryWrapper<CourseStore>()
                    .eq(CourseStore::getCourseId, courseId)
                    .eq(CourseStore::getStoreId, storeId)
                    .eq(CourseStore::getStatus, 1));
            if (relation != null) {
                // 查门店：主键一条。★门店名要进订单快照，营业时间给批量版算可约区间用
                store = storeMapper.selectById(storeId);
            }
        }

        // 可售判断、第二层时间、规则文案全部交给 assemble()：
        // ★单条版与批量版共用一份组装口径，两边不会出现"一个算在卖一个算不在"的分叉
        return assemble(course, relation, store, storeId, LocalDateTime.now());
    }

    /**
     * {@inheritDoc}
     *
     * <p>★批量版本<b>不抛"课程不存在"</b>：购物车里出现一门课被下架是正常现象，
     * 正确表现是"该条目显示已下架、不能勾选结算"，而不是让整个购物车页面打不开。
     * 单条版（建单前校验）恰恰相反——它必须硬失败，不能让用户买到下架的课。
     * <b>同一个数据在两条链路上的容错要求是相反的，所以是两个方法而不是一个加布尔参数。</b></p>
     */
    @Override
    public List<CourseDTO> getBatchDetail(List<CourseStoreKeyDTO> keys) {
        List<CourseDTO> result = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return result;
        }

        // ===== 第 1 步：把两批 id 去重出来 =====
        List<Long> courseIds = new ArrayList<>();
        List<Long> storeIds = new ArrayList<>();
        for (CourseStoreKeyDTO key : keys) {
            if (key.getCourseId() != null && !courseIds.contains(key.getCourseId())) {
                courseIds.add(key.getCourseId());
            }
            if (key.getStoreId() != null && !storeIds.contains(key.getStoreId())) {
                storeIds.add(key.getStoreId());
            }
        }

        // ===== 第 2 步：三条 SQL 拿全（课程、关系、门店），★不是每对一次查询 =====
        Map<Long, Course> courseMap = new HashMap<>();
        if (!courseIds.isEmpty()) {
            for (Course course : courseMapper.selectBatchIds(courseIds)) {
                courseMap.put(course.getId(), course);
            }
        }
        Map<String, CourseStore> relationMap = new HashMap<>();
        if (!courseIds.isEmpty() && !storeIds.isEmpty()) {
            List<CourseStore> relations = courseStoreMapper.selectList(new LambdaQueryWrapper<CourseStore>()
                    .in(CourseStore::getCourseId, courseIds)
                    .in(CourseStore::getStoreId, storeIds));
            for (CourseStore relation : relations) {
                relationMap.put(relation.getCourseId() + "_" + relation.getStoreId(), relation);
            }
        }
        Map<Long, Store> storeMap = new HashMap<>();
        if (!storeIds.isEmpty()) {
            for (Store store : storeMapper.selectBatchIds(storeIds)) {
                storeMap.put(store.getId(), store);
            }
        }

        // ===== 第 3 步：按入参顺序组装，查不到的直接跳过（调用方按缺失判"已下架"）=====
        LocalDateTime now = LocalDateTime.now();
        for (CourseStoreKeyDTO key : keys) {
            Course course = courseMap.get(key.getCourseId());
            if (course == null) {
                continue;
            }
            CourseStore relation = relationMap.get(key.getCourseId() + "_" + key.getStoreId());
            result.add(assemble(course, relation, storeMap.get(key.getStoreId()),
                    key.getStoreId(), now));
        }
        return result;
    }

    /**
     * 把"课程 + 该店关系行 + 门店"拼成跨服务快照。
     * <p>★单条与批量共用这一份组装，可售判断与规则文案的口径才不会分叉。</p>
     */
    private CourseDTO assemble(Course course, CourseStore relation, Store store,
                               Long storeId, LocalDateTime now) {
        // ★门店必须存在且营业中。以前只判"课程状态 + 课程门店关系"，
        // 门店被删除/停用后关系还在，onSale 仍然是 true，用户能买到一家不存在的店。
        boolean storeOpen = store != null && store.getStatus() != null && store.getStatus() == 1;
        boolean onSale = course.getStatus() != null && course.getStatus() == 1
                && storeOpen && sellableNow(relation, now);

        String scene = ruleDescribeFactory.courseSceneOf(course.getCourseKind(), course.getSaleType());
        RuleContext ruleContext = buildRuleContext(course, relation);

        return CourseDTO.builder()
                .id(course.getId())
                .name(course.getName())
                .courseKind(course.getCourseKind())
                .type(course.getType())
                .lessonHours(course.getLessonHours())
                .saleType(course.getSaleType())
                .coverColor(course.getCoverColor())
                .timesPerBuy(course.getTimesPerBuy())
                .duration(course.getDuration())
                .calories(course.getCalories())
                .price(course.getPrice())
                .storeId(storeId)
                .storeName(store == null ? null : store.getName())
                .onSale(onSale)
                .validType(relation == null ? null : relation.getValidType())
                .validDays(relation == null ? null : relation.getValidDays())
                .useStartTime(relation == null ? null : relation.getUseStartTime())
                .useEndTime(relation == null ? null : relation.getUseEndTime())
                .ruleTexts(ruleDescribeFactory.describe(scene, ruleContext))
                .ruleSummary(ruleDescribeFactory.summary(scene, ruleContext))
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现上只是"实体 + 活动 + 一次门店数查询"的组合，把 {@link #toVO} 暴露出来给
     * 秒杀列表等本模块调用方复用。</p>
     */
    @Override
    public CourseVO buildCourseVO(Course course, SeckillActivity activity) {
        int storeCount = loadStoreCounts(List.of(course.getId())).getOrDefault(course.getId(), 0);
        return toVO(course, activity, storeCount);
    }

    // =====================================================================
    // 管理端（fitness-admin-server 经 Feign 调用）；改完主动失效缓存
    // =====================================================================
    @Override
    public PageDTO<CourseAdminDTO> adminPage(String type, String kind, Integer status,
                                             String keyword, Integer page, Integer size) {
        // 分页查课程：MP 的 PaginationInnerInterceptor 自动补 LIMIT 与 COUNT(*)
        // ★kind（可售课/展示课）与 status（上架/下架）是这一版补上的两个筛选项：
        //   展示型课程（切片 H）进来之后，"只看在售的课"和"只看卖课时"是两种完全不同的操作
        Page<Course> result = courseMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null || size < 1 ? 10 : Math.min(size, 50)),
                new LambdaQueryWrapper<Course>()
                        .eq(type != null && !type.isBlank() && !TYPE_ALL.equals(type), Course::getType, type)
                        .eq(kind != null && !kind.isBlank() && !TYPE_ALL.equals(kind), Course::getCourseKind, kind)
                        .eq(status != null, Course::getStatus, status)
                        .like(keyword != null && !keyword.isBlank(), Course::getName, keyword)
                        .orderByDesc(Course::getId));

        if (result.getRecords().isEmpty()) {
            return PageDTO.of(List.of(), result.getTotal());
        }
        List<Long> courseIds = result.getRecords().stream().map(Course::getId).toList();

        // ===== 整页的门店窗口一次查完，再按 courseId 分组 =====
        // ★storeCount 直接从这份分组里数出来，不再单独跑一次 COUNT：
        //   同一次查询的结果既能算数又能填表单，两处口径天然一致
        List<CourseStore> windows = courseStoreMapper.selectList(new LambdaQueryWrapper<CourseStore>()
                .in(CourseStore::getCourseId, courseIds)
                .orderByAsc(CourseStore::getStoreId));
        Map<Long, String> storeNames = windows.isEmpty() ? Map.of()
                : storeMapper.selectBatchIds(windows.stream()
                .map(CourseStore::getStoreId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Store::getId, Store::getName));
        LocalDateTime now = LocalDateTime.now();
        Map<Long, List<CourseWindowAdminDTO>> windowsByCourse = new HashMap<>();
        for (CourseStore window : windows) {
            windowsByCourse.computeIfAbsent(window.getCourseId(), k -> new ArrayList<>())
                    .add(CourseWindowAdminDTO.builder()
                            .id(window.getId())
                            .courseId(window.getCourseId())
                            .storeId(window.getStoreId())
                            .storeName(storeNames.get(window.getStoreId()))
                            .sellStartTime(window.getSellStartTime())
                            .sellEndTime(window.getSellEndTime())
                            .validType(window.getValidType())
                            .validDays(window.getValidDays())
                            .useStartTime(window.getUseStartTime())
                            .useEndTime(window.getUseEndTime())
                            .stock(window.getStock())
                            .status(window.getStatus())
                            .onSaleNow(window.getStatus() != null && window.getStatus() == 1
                                    && (window.getSellEndTime() == null || window.getSellEndTime().isAfter(now)))
                            .build());
        }

        // ===== 整页的会教教练一次查完 =====
        List<CoachCourse> coachRelations = coachCourseMapper.selectList(
                new LambdaQueryWrapper<CoachCourse>().in(CoachCourse::getCourseId, courseIds));
        Map<Long, List<Long>> coachesByCourse = new HashMap<>();
        for (CoachCourse relation : coachRelations) {
            coachesByCourse.computeIfAbsent(relation.getCourseId(), k -> new ArrayList<>())
                    .add(relation.getCoachId());
        }

        List<CourseAdminDTO> rows = new ArrayList<>();
        for (Course course : result.getRecords()) {
            List<CourseWindowAdminDTO> courseWindows =
                    windowsByCourse.getOrDefault(course.getId(), List.of());
            List<Long> coachIds = coachesByCourse.getOrDefault(course.getId(), List.of());
            rows.add(toAdminDTO(course, courseWindows.size(), coachIds, courseWindows));
        }
        bannerService.fillCourseBanners(rows);
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void adminSave(CourseSaveDTO dto) {
        boolean isNew = dto.getId() == null;

        // 新增用新对象；修改先取原记录再覆盖字段——
        // 直接 new 一个 patch 去 updateById 虽然也能"只改非 null 列"，
        // 但把"哪些列该被显式改掉"混在 null 判断里，容易把用户清空的内容当成"没改"
        Course course;
        if (isNew) {
            course = new Course();
            course.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
            course.setCreatedAt(LocalDateTime.now());
        } else {
            course = courseMapper.selectById(dto.getId());
            if (course == null) {
                throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
            }
        }

        course.setName(dto.getName());
        course.setCourseKind(dto.getCourseKind() == null ? "SALE" : dto.getCourseKind());
        course.setType(dto.getType());
        course.setLessonHours(normalizeLessonHours(dto.getLessonHours()));
        course.setDuration(dto.getDuration());
        course.setCalories(dto.getCalories());
        course.setPrice(dto.getPrice());

        // ★售卖形态与课程形态联动：展示型课程（CONTENT）没有"购买"概念，
        // 这两列必须为 null，否则管理端表单切换后残留的旧值会跟着课程走一辈子
        if ("CONTENT".equals(course.getCourseKind())) {
            course.setSaleType(null);
            course.setTimesPerBuy(null);
        } else {
            course.setSaleType(dto.getSaleType() == null ? "TIMES" : dto.getSaleType());
            // UNLIMITED 没有"给几次"的语义，同样置 null
            course.setTimesPerBuy("UNLIMITED".equals(course.getSaleType())
                    ? null : defaultTimesPerBuy(dto.getTimesPerBuy()));
        }

        course.setTags(dto.getTags());
        course.setCoverImage(dto.getCoverImage());
        course.setCoverColor(blankToDefault(dto.getCoverColor(), "0"));
        course.setDescription(dto.getDescription());
        course.setSuitable(dto.getSuitable());
        if (!isNew && dto.getStatus() != null) {
            course.setStatus(dto.getStatus());
        }
        course.setUpdatedAt(LocalDateTime.now());

        if (isNew) {
            courseMapper.insert(course);
        } else {
            int rows = courseMapper.updateById(course);
            if (rows == 0) {
                throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
            }
        }

        // 缓存一致性：改完删详情缓存与空值标记，下次访问自己回源重建。
        // ★用"删除"而不是"更新缓存"：重建 VO 要聚合门店/活动等多个来源，
        //   在写路径上重新拼一遍很容易和读路径不一致；删掉让唯一的读路径负责重建
        redis.delete(RedisKeyConstants.courseDetail(course.getId()));
        redis.delete(RedisKeyConstants.courseEmpty(course.getId()));
        // 同一事务内同步活动页：课程与 Banner 要么一起成功，要么一起回滚
        bannerService.upsertForCourse(course.getId(), dto);
        log.info("管理端保存课程: id={}, name={}, 新增={}", course.getId(), course.getName(), isNew);
    }

    @Override
    public void adminStatus(Long id, Integer status) {
        // 部分更新：只改状态与更新时间（MP 的 updateById 默认忽略 null 字段）
        Course patch = new Course();
        patch.setId(id);
        patch.setStatus(status);
        patch.setUpdatedAt(LocalDateTime.now());
        if (courseMapper.updateById(patch) == 0) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        redis.delete(RedisKeyConstants.courseDetail(id));   // 下架要立即对用户端生效
        redis.delete(RedisKeyConstants.courseEmpty(id));
        if (Integer.valueOf(0).equals(status)) {
            bannerService.disableByCourse(id);
        }
    }

    @Override
    public void adminDelete(Long id) {
        // 物理删除课程。★已售出的凭证与订单不受影响：它们都存了课程名快照
        //（需要历史原值 → 快照，见 docs 第一部分讲解㉒）
        courseMapper.deleteById(id);
        redis.delete(RedisKeyConstants.courseDetail(id));
        redis.delete(RedisKeyConstants.courseEmpty(id));
        bannerService.disableByCourse(id);
        log.info("管理端删除课程: id={}", id);
    }

    // =====================================================================
    // 管理端：课程 × 可售门店
    // =====================================================================
    @Override
    public List<Long> adminStoreIds(Long courseId) {
        // 查关系表：WHERE course_id=? 命中 uk_course_store 最左前缀
        List<CourseStore> relations = courseStoreMapper.selectList(
                new LambdaQueryWrapper<CourseStore>().eq(CourseStore::getCourseId, courseId));

        List<Long> storeIds = new ArrayList<>();
        for (CourseStore relation : relations) {
            storeIds.add(relation.getStoreId());
        }
        return storeIds;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void adminAssignStores(Long courseId, List<Long> storeIds) {
        // 课程必须存在，否则会留下一批指向不存在课程的门店关系
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        if (storeIds == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "storeIds 不能为 null，清空请传空数组");
        }
        // 去重并保留顺序：前端可能传重复门店，去重后只处理一次
        Set<Long> desiredStoreIds = new LinkedHashSet<>(storeIds);

        // ===== 第 1 步：一次查出已有关系，按 storeId 建索引 =====
        // 后面只做差集，不再“先全删再全插”。整体重插的代价不是性能，
        // 而是会把每家店已经配好的售卖窗口、有效期、库存全部重置成默认值。
        List<CourseStore> existing = courseStoreMapper.selectList(
                new LambdaQueryWrapper<CourseStore>().eq(CourseStore::getCourseId, courseId));
        Map<Long, CourseStore> existingByStore = new HashMap<>();
        for (CourseStore relation : existing) {
            existingByStore.put(relation.getStoreId(), relation);
        }

        // ===== 第 2 步：删掉不在新列表里的关系 =====
        // 历史订单/凭证有门店名快照，不受影响。
        for (CourseStore relation : existing) {
            if (!desiredStoreIds.contains(relation.getStoreId())) {
                courseStoreMapper.deleteById(relation.getId());
            }
        }

        // ===== 第 3 步：新列表里的门店，已有关系保留原窗口，没有的才插默认值 =====
        LocalDateTime now = LocalDateTime.now();
        for (Long storeId : desiredStoreIds) {
            if (storeId == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "门店 id 不能为空");
            }
            Store store = storeMapper.selectById(storeId);
            if (store == null || store.getStatus() == null || store.getStatus() != 1) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "门店不存在或已停用：" + storeId);
            }

            CourseStore relation = existingByStore.get(storeId);
            if (relation != null) {
                // 之前可能是下架状态，重新勾选就恢复在售；售卖窗口/有效期/库存一律保留
                if (relation.getStatus() == null || relation.getStatus() != 1) {
                    relation.setStatus(1);
                    relation.setUpdatedAt(now);
                    courseStoreMapper.updateById(relation);
                }
                continue;
            }

            CourseStore created = new CourseStore();
            created.setCourseId(courseId);
            created.setStoreId(storeId);
            created.setSellStartTime(now);          // 默认开售=此刻
            created.setSellEndTime(null);           // 默认长期在售
            created.setValidType("DAYS");           // 默认支付后 30 天内可用
            created.setValidDays(30);
            created.setUseStartTime(null);
            created.setUseEndTime(null);
            created.setStock(null);                 // 默认不限份数
            created.setStatus(1);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            courseStoreMapper.insert(created);
        }

        // 可售门店是详情页要展示的内容，任何一次更新（包括清空）都必须让缓存失效。
        // ★缓存删除失败不能回滚已经提交的配置：记日志，等缓存自然过期即可。
        try {
            redis.delete(RedisKeyConstants.courseDetail(courseId));
        } catch (Exception e) {
            log.error("课程门店关系已更新，但详情缓存删除失败，等待过期: courseId={}", courseId, e);
        }
        log.info("课程可售门店已更新: courseId={}, 门店数={}", courseId, desiredStoreIds.size());
    }

    // =====================================================================
    // 管理端：两层时间窗
    // =====================================================================

    /** 凭证有效期的三种取值（与 {@code t_course_store.valid_type} 一致） */
    private static final String VALID_FOREVER = "FOREVER";
    private static final String VALID_DAYS = "DAYS";
    private static final String VALID_RANGE = "RANGE";

    @Override
    public PageDTO<CourseWindowAdminDTO> adminWindowPage(Long courseId, Long storeId,
                                                         Integer page, Integer size) {
        LambdaQueryWrapper<CourseStore> wrapper = new LambdaQueryWrapper<CourseStore>()
                .eq(courseId != null, CourseStore::getCourseId, courseId)
                .eq(storeId != null, CourseStore::getStoreId, storeId)
                .orderByAsc(CourseStore::getCourseId)
                .orderByAsc(CourseStore::getStoreId);

        Page<CourseStore> pageParam = new Page<>(page == null || page < 1 ? 1 : page,
                size == null || size < 1 ? 10 : Math.min(size, 50));
        courseStoreMapper.selectPage(pageParam, wrapper);
        List<CourseStore> records = pageParam.getRecords();
        if (records.isEmpty()) {
            return PageDTO.of(List.of(), pageParam.getTotal());
        }

        // ===== 整页要补的两个名字，各一次批量查（★不在循环里 selectById）=====
        Set<Long> courseIds = records.stream().map(CourseStore::getCourseId).collect(Collectors.toSet());
        Map<Long, String> courseNames = courseMapper.selectBatchIds(courseIds).stream()
                .collect(Collectors.toMap(Course::getId, Course::getName));
        Set<Long> storeIds = records.stream().map(CourseStore::getStoreId).collect(Collectors.toSet());
        Map<Long, String> storeNames = storeMapper.selectBatchIds(storeIds).stream()
                .collect(Collectors.toMap(Store::getId, Store::getName));

        LocalDateTime now = LocalDateTime.now();
        List<CourseWindowAdminDTO> rows = new ArrayList<>();
        for (CourseStore window : records) {
            // ★onSaleNow 是算出来的、库里没有这一列：下架状态或停售时间已过都算"不在售"。
            //   放在服务端算而不是前端算，是为了让"在售"这件事全站只有一个定义
            boolean onSaleNow = window.getStatus() != null && window.getStatus() == 1
                    && (window.getSellEndTime() == null || window.getSellEndTime().isAfter(now));
            rows.add(CourseWindowAdminDTO.builder()
                    .id(window.getId())
                    .courseId(window.getCourseId())
                    .courseName(courseNames.get(window.getCourseId()))
                    .storeId(window.getStoreId())
                    .storeName(storeNames.get(window.getStoreId()))
                    .sellStartTime(window.getSellStartTime())
                    .sellEndTime(window.getSellEndTime())
                    .validType(window.getValidType())
                    .validDays(window.getValidDays())
                    .useStartTime(window.getUseStartTime())
                    .useEndTime(window.getUseEndTime())
                    .stock(window.getStock())
                    .status(window.getStatus())
                    .onSaleNow(onSaleNow)
                    .build());
        }
        return PageDTO.of(rows, pageParam.getTotal());
    }

    @Override
    public void adminWindowSave(CourseWindowSaveDTO dto) {
        // ===== 第 1 步：只能改已存在的关系行 =====
        CourseStore window = courseStoreMapper.selectById(dto.getId());
        if (window == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "该课程与门店的关系不存在，请先在课程表单里保存门店");
        }

        // ===== 第 2 步：跨字段校验（注解表达不了"条件必填"和区间先后）=====
        if (dto.getSellStartTime() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "开售时间不能为空");
        }
        if (dto.getSellEndTime() != null && !dto.getSellEndTime().isAfter(dto.getSellStartTime())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "停售时间必须晚于开售时间");
        }
        String validType = dto.getValidType() == null ? "" : dto.getValidType().trim().toUpperCase();
        Integer validDays = null;
        LocalDateTime useStart = null;
        LocalDateTime useEnd = null;
        switch (validType) {
            case VALID_FOREVER -> {
                // 永不过期：凭证的 validUntil 会存 null，所以所有过期判断都要带 IS NOT NULL
                validDays = null;
            }
            case VALID_DAYS -> {
                if (dto.getValidDays() == null || dto.getValidDays() <= 0) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "选择「领后 N 天」时必须填写天数");
                }
                validDays = dto.getValidDays();
            }
            case VALID_RANGE -> {
                if (dto.getUseStartTime() == null || dto.getUseEndTime() == null) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "选择「固定起止窗口」时必须同时给起止时间");
                }
                if (!dto.getUseEndTime().isAfter(dto.getUseStartTime())) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "可使用截止时间必须晚于起始时间");
                }
                useStart = dto.getUseStartTime();
                useEnd = dto.getUseEndTime();
            }
            // ★default 直接拒绝而不是当成 FOREVER 兜过去：有效期类型传错如果被静默降级，
            // 后果是"常年课被设成 30 天过期"或反过来，属于会退款扯皮的错，必须当场面红耳赤地报出来
            default -> throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "有效期类型只能是 FOREVER / DAYS / RANGE");
        }

        // ===== 第 3 步：显式 UPDATE，允许把字段清成 NULL =====
        // ★不能用 updateById：MyBatis-Plus 默认忽略 null 字段，
        //   “把停售时间改回长期在售（sell_end_time=null）”“把库存改回不限（stock=null）”
        //   都会被静默丢掉，管理员会以为保存成功、实际旧值还在。
        //   LambdaUpdateWrapper 的 set 会把 null 写成真正的 SQL NULL。
        LambdaUpdateWrapper<CourseStore> update = new LambdaUpdateWrapper<CourseStore>()
                .eq(CourseStore::getId, window.getId())
                .set(CourseStore::getSellStartTime, dto.getSellStartTime())
                .set(CourseStore::getSellEndTime, dto.getSellEndTime())
                .set(CourseStore::getValidType, validType)
                .set(CourseStore::getValidDays, validDays)
                .set(CourseStore::getUseStartTime, useStart)
                .set(CourseStore::getUseEndTime, useEnd)
                .set(CourseStore::getStock, dto.getStock())
                .set(CourseStore::getStatus, dto.getStatus() == null ? window.getStatus() : dto.getStatus())
                .set(CourseStore::getUpdatedAt, LocalDateTime.now());
        int updated = courseStoreMapper.update(null, update);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "课程门店关系不存在，保存失败");
        }

        // ===== 第 4 步：窗口变了，课程详情缓存必须失效（可售状态、最近可购时间都在里面）=====
        redis.delete(RedisKeyConstants.courseDetail(window.getCourseId()));
        log.info("时间窗已更新: id={}, courseId={}, storeId={}",
                window.getId(), window.getCourseId(), window.getStoreId());
    }

    // =====================================================================
    // 管理端：看板计数
    // =====================================================================

    @Override
    public AdminCountDTO adminCount() {
        long courseCount = courseMapper.selectCount(new LambdaQueryWrapper<Course>()
                .eq(Course::getStatus, 1));
        long storeCount = storeMapper.selectCount(new LambdaQueryWrapper<Store>()
                .eq(Store::getStatus, 1));

        // 教练只有几十位量级，一次查出来在内存里判"今天上不上班"。
        // ★没有把 workDays 拆成表或位图：那是为一万位教练准备的方案，这里读一次全表比
        //   多建一张表再维护同步便宜得多
        List<Coach> bookableCoaches = coachMapper.selectList(new LambdaQueryWrapper<Coach>()
                .eq(Coach::getStatus, 1));
        int todayOfWeek = LocalDate.now().getDayOfWeek().getValue();   // 1=周一 … 7=周日
        int slotTotal = 0;
        for (Coach coach : bookableCoaches) {
            if (!workDaysContains(coach.getWorkDays(), todayOfWeek)) {
                continue;   // 今天休息的教练不进分母，否则占用率被稀释
            }
            Integer start = coach.getWorkStartHour();
            Integer end = coach.getWorkEndHour();
            if (start != null && end != null && end > start) {
                slotTotal += end - start;
            }
        }

        return AdminCountDTO.builder()
                .courseCount((int) courseCount)
                .coachCount(bookableCoaches.size())
                .storeCount((int) storeCount)
                .todaySlotTotal(slotTotal)
                .build();
    }

    /** workDays 是 "1,3,5" 这样的串；为空按"全周可约"处理（与 CoachServiceImpl 的默认值同口径） */
    private boolean workDaysContains(String workDays, int dayOfWeek) {
        if (workDays == null || workDays.isBlank()) {
            return true;
        }
        for (String part : workDays.split(",")) {
            if (part.trim().equals(String.valueOf(dayOfWeek))) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /**
     * 这门课在这个门店此刻能不能卖。
     *
     * <p>★判定只写在这一处，{@code assemble()}（建单前的内部校验）和详情页的门店列表
     * 共用同一个口径。两边各写一遍迟早分叉成"接口说在卖、详情页说停售"。</p>
     *
     * <p>注意这里<b>不判课程自身的上架状态</b>：调用方要么已经在 {@code getDetail} 里
     * 拦掉了下架课程（{@code COURSE_NOT_FOUND}），要么像 {@code assemble} 那样自己补上这一层。</p>
     */
    private boolean sellableNow(CourseStore relation, LocalDateTime now) {
        return relation != null
                && relation.getStatus() != null && relation.getStatus() == 1
                && relation.getSellStartTime() != null && !relation.getSellStartTime().isAfter(now)
                && (relation.getSellEndTime() == null || !relation.getSellEndTime().isBefore(now));
    }

    /**
     * 详情页门店上下文：一次查询拿全门店关系，同时返回：
     * <ul>
     *   <li>门店下拉项（停售门店也返回，用 {@code onSale=false} 标灰）；</li>
     *   <li>目标门店关系（规则上下文用，只收 {@code status=1}）；</li>
     *   <li>在售门店数（口径 = {@code status=1} 的关系数，不是 onSale）。</li>
     * </ul>
     *
     * <p>★合并详情请求内的重复查询：原来 loadStoreOptions 和 selectOne 在同一个请求里
     * 对 {@code t_course_store} 查两次，现在一次拿全。</p>
     */
    private StoreContext loadStoreContext(Long courseId) {
        List<CourseStore> relations = courseStoreMapper.selectList(
                new LambdaQueryWrapper<CourseStore>().eq(CourseStore::getCourseId, courseId));
        if (relations.isEmpty()) {
            return new StoreContext(List.of(), Map.of(), 0);
        }
        Set<Long> storeIds = relations.stream()
                .map(CourseStore::getStoreId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Store> stores = storeIds.isEmpty() ? Map.of()
                : storeMapper.selectBatchIds(storeIds).stream()
                .collect(Collectors.toMap(Store::getId, s -> s, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        List<CourseVO.StoreOption> options = new ArrayList<>(relations.size());
        Map<Long, CourseStore> relationByStore = new HashMap<>();
        int activeStoreCount = 0;
        for (CourseStore relation : relations) {
            Store store = stores.get(relation.getStoreId());
            // 门店不存在或 status=0 都不可售；关系行保留是为了让用户看到“这家店已关闭”而不是整行消失
            boolean storeOpen = store != null && store.getStatus() != null && store.getStatus() == 1;
            options.add(CourseVO.StoreOption.builder()
                    .storeId(relation.getStoreId())
                    .storeName(store == null ? "门店已关闭" : store.getName())
                    .onSale(storeOpen && sellableNow(relation, now))
                    .openHoursText(store == null ? null : store.getOpenHoursText())
                    .build());
            // storeCount 口径：status=1 的关系数；onSale 还受售卖时间窗影响，不能混用
            if (Integer.valueOf(1).equals(relation.getStatus())) {
                activeStoreCount++;
                if (relation.getStoreId() != null) {
                    relationByStore.putIfAbsent(relation.getStoreId(), relation);
                }
            }
        }
        // 在卖的排前面，同组按门店 id 稳定排序（否则每次刷新顺序会跳）
        options.sort(Comparator.comparing((CourseVO.StoreOption o) -> !Boolean.TRUE.equals(o.getOnSale()))
                .thenComparing(CourseVO.StoreOption::getStoreId));
        return new StoreContext(options, relationByStore, activeStoreCount);
    }

    /** loadStoreContext 的返回值：门店下拉项 + 目标门店关系 + 在售门店数 */
    private record StoreContext(List<CourseVO.StoreOption> options,
                                Map<Long, CourseStore> relationByStore,
                                int activeStoreCount) {
    }

    /** 一次取回这些课程的可售门店数（★绝不在循环里查库） */
    private Map<Long, Integer> loadStoreCounts(List<Long> courseIds) {
        List<CourseStore> relations = courseStoreMapper.selectList(
                new LambdaQueryWrapper<CourseStore>()
                        .in(CourseStore::getCourseId, courseIds)
                        .eq(CourseStore::getStatus, 1));

        Map<Long, Integer> counts = new HashMap<>();
        for (CourseStore relation : relations) {
            Integer current = counts.get(relation.getCourseId());
            counts.put(relation.getCourseId(), current == null ? 1 : current + 1);
        }
        return counts;
    }

    /**
     * 进行中的活动 → courseId 映射。
     *
     * <p>★这里以前用 {@code Collectors.toMap} 且没给合并函数：同一门课配了两个"进行中"活动时
     * 会抛 {@code IllegalStateException: Duplicate key}，整个列表接口 500。
     * 现在保留 id 较大的那个（较新的一次活动），并让这种情况在日志里可见。</p>
     */
    private Map<Long, SeckillActivity> activeActivityMap() {
        List<SeckillActivity> activities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getStatus, 1));

        Map<Long, SeckillActivity> map = new HashMap<>();
        for (SeckillActivity activity : activities) {
            SeckillActivity existed = map.get(activity.getCourseId());
            if (existed == null) {
                map.put(activity.getCourseId(), activity);
                continue;
            }
            log.warn("同一课程存在多个进行中的秒杀活动，取较新的一个: courseId={}, 保留={}, 忽略={}",
                    activity.getCourseId(),
                    activity.getId() > existed.getId() ? activity.getId() : existed.getId(),
                    activity.getId() > existed.getId() ? existed.getId() : activity.getId());
            if (activity.getId() > existed.getId()) {
                map.put(activity.getCourseId(), activity);
            }
        }
        return map;
    }

    /** 实体 → 管理端行 DTO（列表展示 + 编辑回填） */
    private CourseAdminDTO toAdminDTO(Course course, int storeCount, List<Long> coachIds,
                                      List<CourseWindowAdminDTO> storeWindows) {
        return CourseAdminDTO.builder()
                .id(course.getId())
                .name(course.getName())
                .courseKind(course.getCourseKind())
                .type(course.getType())
                .lessonHours(course.getLessonHours())
                .duration(course.getDuration())
                .calories(course.getCalories())
                .price(course.getPrice())
                .saleType(course.getSaleType())
                .timesPerBuy(course.getTimesPerBuy())
                .tags(course.getTags())
                .coverImage(course.getCoverImage())
                .coverColor(course.getCoverColor())
                .description(course.getDescription())
                .suitable(course.getSuitable())
                .status(course.getStatus())
                .storeCount(storeCount)
                .coachCount(coachIds.size())
                .coachIds(coachIds)
                .storeWindows(storeWindows)
                .build();
    }

    /** 实体 + 活动 + 门店数 → C 端 VO */
    private CourseVO toVO(Course course, SeckillActivity activity, int storeCount) {
        List<String> tags = new ArrayList<>();
        if (course.getTags() != null && !course.getTags().isBlank()) {
            // 逗号串在后端拆成数组：否则每个页面都要各自 split 一次，格式还不一定一致
            for (String tag : course.getTags().split(",")) {
                if (!tag.isBlank()) {
                    tags.add(tag.trim());
                }
            }
        }

        CourseVO.CourseVOBuilder builder = CourseVO.builder()
                .id(course.getId())
                .name(course.getName())
                .kind(course.getCourseKind())
                .type(course.getType())
                .lessonHours(course.getLessonHours())
                .saleType(course.getSaleType())
                .timesPerBuy(course.getTimesPerBuy())
                .duration(course.getDuration())
                .calories(course.getCalories())
                .price(course.getPrice())
                .priceText(formatPrice(course.getPrice()))
                .tags(tags)
                .coverImage(course.getCoverImage())
                .coverColor(course.getCoverColor())
                .storeCount(storeCount)
                .description(course.getDescription())
                .suitable(course.getSuitable())
                .saleTypeText(saleTypeText(course))
                // ★列表页的 latestFreeText 用"几家家门店可约"而不是教练数：
                // 同一门课在两家店的教练是两拨人，不带 storeId 时给一个混合总数会误导。
                // 详情页带 storeId 时由 withStoreContext 换成该店的教练数
                .latestFreeText(storeCount > 0 ? storeCount + " 家门店可约" : "暂无可约门店")
                .actions(buildActions(course.getCourseKind(), activity))
                // 规则条目与摘要都由策略产出。没有门店上下文时有效期那一句会被策略自己省略，
                // 而不是编一个"长期有效"骗用户
                .ruleTexts(ruleDescribeFactory.describe(
                ruleDescribeFactory.courseSceneOf(course.getCourseKind(), course.getSaleType()),
                        buildRuleContext(course, null)))
                .ruleSummary(ruleDescribeFactory.summary(
                        ruleDescribeFactory.courseSceneOf(course.getCourseKind(), course.getSaleType()),
                        buildRuleContext(course, null)));

        if (activity != null && activity.getEndTime() != null) {
            builder.seckill(CourseVO.SeckillInfo.builder()
                    .activityId(activity.getId())
                    .price(activity.getSeckillPrice())
                    .priceText(formatPrice(activity.getSeckillPrice()))
                    .total(activity.getTotal())
                    .remain(activity.getRemain())
                    .endTime(toEpochMillis(activity.getEndTime()))
                    .build());
        }
        return builder.build();
    }

    /**
     * 售卖形态文案。CONTENT 课没有售卖形态，给 null 而不是"未知"——
     * 前端对 CONTENT 卡片根本不渲染这一行。
     */
    private String saleTypeText(Course course) {
        if ("CONTENT".equals(course.getCourseKind())) {
            return null;
        }
        return "UNLIMITED".equals(course.getSaleType()) ? "不限次" : "按次购买";
    }

    /**
     * 卡片按钮。★由后端算：秒杀是"这一刻有没有活动"决定的，
     * 前端自己判会在活动刚结束的那几十秒里给出一个买不了的按钮。
     */
    private List<String> buildActions(String courseKind, SeckillActivity activity) {
        List<String> actions = new ArrayList<>();
        if ("CONTENT".equals(courseKind)) {
            actions.add("FREE");
            return actions;
        }
        actions.add("BUY");
        if (activity != null) {
            actions.add("SECKILL");
        }
        return actions;
    }

    /**
     * 组装规则文案的输入。
     * <p>★注意这里<b>没有</b>把 CourseStore 塞进策略去查——关系行由调用方查好传进来，
     * 策略只吃现成字段。这是"策略不查库"那条线的具体执行方式：
     * 查数据的代码集中在这里，Service 本来就干这个。</p>
     *
     * @param relation 课程在该门店的关系行，列表页没有门店上下文时传 null
     */
    private RuleContext buildRuleContext(Course course, CourseStore relation) {
        return RuleContext.builder()
                .courseKind(course.getCourseKind())
                .saleType(course.getSaleType())
                .lessonHours(course.getLessonHours())
                .timesPerBuy(course.getTimesPerBuy())
                .validType(relation == null ? null : relation.getValidType())
                .validDays(relation == null ? null : relation.getValidDays())
                .useEndTime(relation == null ? null : relation.getUseEndTime())
                .build();
    }

    /**
     * 批量填评分摘要。
     *
     * <p>★一次 RPC 拿一页的分数，<b>绝不在循环里逐门课调 Feign</b>
     * （那是 N+1 的网络版，比循环查库更贵：每次都是一整套 HTTP 往返）。</p>
     *
     * <p>取不到时只打 warn、字段留 null，<b>不让评分拖垮列表</b>：
     * 课程列表是这个 App 的门面，order 服务抖一下就让首页开不了是本末倒置。
     * null 在前端渲染成"暂无评分"，和"0 分"是两件事。</p>
     */
    private void fillCourseRatings(List<CourseVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (CourseVO vo : vos) {
            if (vo.getId() != null && !ids.contains(vo.getId())) {
                ids.add(vo.getId());
            }
        }
        try {
            Result<List<RatingStatsDTO>> result =
                    ratingClient.stats("COURSE", ids);
            if (result.getCode() != 0 || result.getData() == null) {
                return;
            }
            Map<Long, RatingStatsDTO> statsMap = new HashMap<>();
            for (RatingStatsDTO stat : result.getData()) {
                statsMap.put(stat.getTargetId(), stat);
            }
            for (CourseVO vo : vos) {
                RatingStatsDTO stat = statsMap.get(vo.getId());
                if (stat != null) {
                    vo.setRatingText(stat.getAvgText());
                    vo.setRatingCount(stat.getRatingCount());
                }
            }
        } catch (Exception e) {
            log.warn("取课程评分失败，列表按\"暂无评分\"渲染: ids={}", ids, e);
        }
    }

    /** lessonHours 兜底：不在 1~3 内一律按 1。★表单是下拉框，正常不会走到这里 */
    private Integer normalizeLessonHours(Integer hours) {
        return (hours == null || hours < 1 || hours > 3) ? 1 : hours;
    }

    /** TIMES 形态下每单给几次，缺省 1 */
    private Integer defaultTimesPerBuy(Integer timesPerBuy) {
        return (timesPerBuy == null || timesPerBuy < 1) ? 1 : timesPerBuy;
    }

    private String blankToDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    /** 金额格式化统一在后端做：三个页面三套 toFixed 是常见问题 */
    private String formatPrice(BigDecimal price) {
        return price == null ? null : String.format("¥%.2f", price);
    }

    /** LocalDateTime → 毫秒时间戳。前端倒计时只用数字，不参与时区解析 */
    private Long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
