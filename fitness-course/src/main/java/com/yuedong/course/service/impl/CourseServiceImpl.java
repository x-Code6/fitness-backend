package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.cache.CacheWrapper;
import com.yuedong.course.mapper.CoachMapper;
import com.yuedong.course.mapper.CourseMapper;
import com.yuedong.course.mapper.CourseStoreMapper;
import com.yuedong.course.mapper.SeckillActivityMapper;
import com.yuedong.course.mapper.StoreMapper;
import com.yuedong.course.model.entity.Course;
import com.yuedong.course.model.entity.CourseStore;
import com.yuedong.course.model.entity.SeckillActivity;
import com.yuedong.course.model.entity.Store;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.course.service.CourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 课程业务实现（v2）。
 *
 * <p>读本类的三个入口：</p>
 * <ol>
 *   <li><b>缓存三件套</b>（{@link #getDetail}）：防击穿=逻辑过期 + 互斥锁 + 异步重建，
 *       防穿透=空值缓存，防雪崩=物理 TTL + 随机抖动。
 *       旧实现漏了第三件：{@code writeCache} 用的是不带 TTL 的 set，key 只增不减；</li>
 *   <li><b>门店维度的数据不进缓存</b>：同一门课在 A 店有 3 位教练、在 B 店可能 0 位。
 *       按 {@code (courseId, storeId)} 做键会让键空间随门店数膨胀且大多很冷，
 *       所以缓存只存与门店无关的部分，"几位教练可约"每次走一次索引 COUNT；</li>
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

    /** 互斥锁的持有时长（秒）。不带 leaseTime 的话要靠 Redisson 看门狗续期，进程被 kill -9 时释放很慢 */
    private static final long LOCK_LEASE_SECONDS = 10;

    /** 课程分类里表示"不加筛选"的前端约定值 */
    private static final String TYPE_ALL = "全部";

    private final CourseMapper courseMapper;
    private final CourseStoreMapper courseStoreMapper;
    private final CoachMapper coachMapper;
    private final StoreMapper storeMapper;
    private final SeckillActivityMapper seckillActivityMapper;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    /** 缓存重建线程池（配置见 CacheRebuildExecutorConfig：队列 64 + DiscardPolicy） */
    @Qualifier("cacheRebuildExecutor")
    private final ExecutorService rebuildExecutor;

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
     * <p>三个细节都写在代码里：</p>
     * <ul>
     *   <li>锁用<b>独立 key</b>（{@code course:lock:detail:}）而不是复用数据 key——旧实现拿数据 key 加锁，
     *       锁自身的内部结构与缓存 JSON 挤在同一个键上；</li>
     *   <li>{@code tryLock(0, ...)}：等 0 秒，抢不到立刻走人（去返回旧数据），
     *       不排队——排队就把"异步重建不阻塞用户"这个优点抵消了；</li>
     *   <li>带 leaseTime，避免进程被强杀后锁迟迟不放。</li>
     * </ul>
     */
    private void tryRebuildAsync(Long id) {
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
            rebuildExecutor.submit(() -> rebuildCache(id));
            log.info("课程详情逻辑过期，已提交异步重建: id={}", id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 恢复中断标记，不要吞掉
            log.warn("等待课程缓存锁被中断: id={}", id);
        } finally {
            // 只解自己拿到的锁；isHeldByCurrentThread 防止解掉别人的锁
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
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
     * 给缓存取出的 VO 补上门店维度的实时字段（当前只有"几位教练可约"）。
     *
     * <p>缓存里的对象是共享的反序列化结果，每次请求都会重新 new 一个，
     * 所以在这里 set 不会污染缓存内容。</p>
     */
    private CourseVO withStoreContext(CourseVO vo, Long storeId) {
        if (vo == null || storeId == null) {
            return vo;
        }
        // 三表同库 JOIN 数教练：命中 t_coach_course.idx_course 与 t_coach.uk_store_name 前缀
        vo.setCoachCount(coachMapper.countByCourseAndStore(vo.getId(), storeId));
        return vo;
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
        String storeName = null;
        if (storeId != null) {
            relation = courseStoreMapper.selectOne(new LambdaQueryWrapper<CourseStore>()
                    .eq(CourseStore::getCourseId, courseId)
                    .eq(CourseStore::getStoreId, storeId)
                    .eq(CourseStore::getStatus, 1));
            if (relation != null) {
                // 查门店名：WHERE id=? 命中主键。★这是"订单要存门店快照"的数据来源，
                // 不参与任何并发判断
                Store store = storeMapper.selectById(storeId);
                storeName = store == null ? null : store.getName();
            }
        }

        // 可售窗口判断：在卖 且 未过停售时间（sellEndTime 为 null 表示长期在售）
        LocalDateTime now = LocalDateTime.now();
        boolean onSale = relation != null
                && relation.getSellStartTime() != null && !relation.getSellStartTime().isAfter(now)
                && (relation.getSellEndTime() == null || !relation.getSellEndTime().isBefore(now));

        return CourseDTO.builder()
                .id(course.getId())
                .name(course.getName())
                .courseKind(course.getCourseKind())
                .type(course.getType())
                .lessonHours(course.getLessonHours())
                .saleType(course.getSaleType())
                .timesPerBuy(course.getTimesPerBuy())
                .duration(course.getDuration())
                .calories(course.getCalories())
                .price(course.getPrice())
                .storeId(storeId)
                .storeName(storeName)
                .onSale(onSale)
                // ★第二层时间原样带给 order：它只负责"算出 valid_until"，不负责解释三种类型。
                // 关系行缺失（课程没配到这家店）时全部给 null，由 order 侧按 FOREVER 之外的
                // 保守分支处理——可售判断已经先把这种单子拦下了，这里只是不留 NPE 隐患
                .validType(relation == null ? null : relation.getValidType())
                .validDays(relation == null ? null : relation.getValidDays())
                .useStartTime(relation == null ? null : relation.getUseStartTime())
                .useEndTime(relation == null ? null : relation.getUseEndTime())
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
    public PageDTO<CourseAdminDTO> adminPage(String type, String keyword, Integer page, Integer size) {
        // 分页查课程：MP 的 PaginationInnerInterceptor 自动补 LIMIT 与 COUNT(*)
        Page<Course> result = courseMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 10 : size),
                new LambdaQueryWrapper<Course>()
                        .eq(type != null && !type.isBlank() && !TYPE_ALL.equals(type), Course::getType, type)
                        .like(keyword != null && !keyword.isBlank(), Course::getName, keyword)
                        .orderByDesc(Course::getId));

        if (result.getRecords().isEmpty()) {
            return PageDTO.of(List.of(), result.getTotal());
        }

        Map<Long, Integer> storeCountMap = loadStoreCounts(
                result.getRecords().stream().map(Course::getId).toList());

        List<CourseAdminDTO> rows = new ArrayList<>();
        for (Course course : result.getRecords()) {
            rows.add(toAdminDTO(course, storeCountMap.getOrDefault(course.getId(), 0)));
        }
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
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
    }

    @Override
    public void adminDelete(Long id) {
        // 物理删除课程。★已售出的凭证与订单不受影响：它们都存了课程名快照
        //（需要历史原值 → 快照，见 docs 第一部分讲解㉒）
        courseMapper.deleteById(id);
        redis.delete(RedisKeyConstants.courseDetail(id));
        redis.delete(RedisKeyConstants.courseEmpty(id));
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

        // 先删后插的整体替换：配课量小（一门课几家店），不值得做差异计算
        courseStoreMapper.delete(new LambdaQueryWrapper<CourseStore>()
                .eq(CourseStore::getCourseId, courseId));

        if (storeIds.isEmpty()) {
            log.info("课程可售门店已清空: courseId={}", courseId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (Long storeId : storeIds) {
            // 门店必须存在：下拉框选的一般都存在，但课程与门店可能被别的管理员刚删掉
            if (storeMapper.selectById(storeId) == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "门店不存在：" + storeId);
            }
            CourseStore relation = new CourseStore();
            relation.setCourseId(courseId);
            relation.setStoreId(storeId);
            relation.setSellStartTime(now);          // 默认开售=此刻
            relation.setSellEndTime(null);           // 默认长期在售
            relation.setValidType("DAYS");           // 默认领后 30 天内可用
            relation.setValidDays(30);
            relation.setUseStartTime(null);
            relation.setUseEndTime(null);
            relation.setStock(null);                 // 默认不限份数
            relation.setStatus(1);
            relation.setCreatedAt(now);
            relation.setUpdatedAt(now);
            try {
                courseStoreMapper.insert(relation);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 前端传了重复门店 id：已存在的就是我们要的状态，跳过即可
                log.warn("可售门店重复，已忽略: courseId={}, storeId={}", courseId, storeId);
            }
        }

        // 可售门店是详情页要展示的内容，改完必须让缓存失效
        redis.delete(RedisKeyConstants.courseDetail(courseId));
        log.info("课程可售门店已更新: courseId={}, 门店数={}", courseId, storeIds.size());
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

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
    private CourseAdminDTO toAdminDTO(Course course, int storeCount) {
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
                .ruleSummary(buildRuleSummary(course, storeCount));

        // ★ruleTexts 这里留 null：整块规则条目由"规则说明策略"生成（docs 4.3.5）。
        // 在本类里手写一遍，将来策略实现后就有两处文案，必然漂移

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
     * 列表用的一句话规则摘要：只讲用户最关心的三件事（多久一节 / 一单几次 / 几家店能约）。
     * 完整条目在详情页由策略生成，这里不重复，避免两处文案不一致。
     */
    private String buildRuleSummary(Course course, int storeCount) {
        if ("CONTENT".equals(course.getCourseKind())) {
            return "免费跟练 · 无需购买";
        }
        StringBuilder text = new StringBuilder();
        text.append(course.getLessonHours()).append(" 小时/节");
        if ("UNLIMITED".equals(course.getSaleType())) {
            text.append(" · 有效期内不限次");
        } else if (course.getTimesPerBuy() != null && course.getTimesPerBuy() > 1) {
            text.append(" · 一次购买 ").append(course.getTimesPerBuy()).append(" 次");
        }
        text.append(" · ").append(storeCount).append(" 家门店可约");
        return text.toString();
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
