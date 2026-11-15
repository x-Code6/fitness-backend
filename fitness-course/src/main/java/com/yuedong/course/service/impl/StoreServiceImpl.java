package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.course.api.dto.StoreSaveDTO;
import com.yuedong.course.mapper.CoachMapper;
import com.yuedong.course.mapper.CourseStoreMapper;
import com.yuedong.course.mapper.StoreMapper;
import com.yuedong.course.model.entity.Coach;
import com.yuedong.course.model.entity.CourseStore;
import com.yuedong.course.model.entity.Store;
import com.yuedong.course.model.vo.StoreVO;
import com.yuedong.course.service.StoreService;
import com.yuedong.order.api.client.RatingClient;
import com.yuedong.order.api.dto.RatingStatsDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店业务实现：Redis GEO 实现"附近门店按距离排序"。
 *
 * <p>链路：启动时把门店经纬度 GEOADD 进 Redis（key={@code store:geo}，member=门店 id）→
 * 查询时 GEOSEARCH（以用户坐标为圆心、按距离升序、带距离）→ 回表查门店信息 →
 * 关键词过滤 → 组装 VO。</p>
 *
 * <p>为什么用 GEO 不用 SQL：GEO 底层是 ZSet（把 geohash 编码成 score），
 * "按距离排序 + 范围检索"是 O(logN) 内存操作，比在 SQL 里逐行算球面距离快得多。</p>
 *
 * <p>★v2 在本类里改了三件事：</p>
 * <ul>
 *   <li>评分不再来自 {@code t_store.rating}（该列已删）。评价数据在 yd_order，
 *       要跨服务批量取；本期先留 null，前端按"暂无评分"渲染，
 *       <b>绝不能填 0</b>——0 分的意思是完全相反的一件事。</li>
 *   <li>教练数与在售课程数改为<b>实时数出来</b>，不再读 {@code t_store.coaches} 这种写死列。
 *       教练已成实体、课程关系已在 {@code t_course_store}，再维护一份计数就是第二份真相。</li>
 *   <li>营业时间拆成计算列（{@code openStartHour/openEndHour/is24h}）+ 展示文案列，
 *       VO 只给文案，计算留给时段接口用。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService, ApplicationRunner {

    /**
     * 演示用用户位置：郑州二七广场。
     * <p>★真实项目要由前端传经纬度（用户授权定位）。目前是写死的，
     * 所以"附近门店"的距离排序对所有用户都一样——这一点在演示时要如实说明，
     * 不要说成"实现了基于位置的门店检索"（GEO 查询本身是真的，变的是圆心）。</p>
     */
    private static final double USER_LNG = 113.660818;
    private static final double USER_LAT = 34.747307;

    /** 检索半径（公里） */
    private static final double SEARCH_RADIUS_KM = 50;

    /** 一次最多返回几家门店 */
    private static final int SEARCH_LIMIT = 20;

    private final StoreMapper storeMapper;
    private final CoachMapper coachMapper;
    private final CourseStoreMapper courseStoreMapper;
    private final StringRedisTemplate redis;
    /** 门店评分来自 order 域（评价明细与统计都在 yd_order），这里只读 */
    private final RatingClient ratingClient;

    /** 应用启动完成后把全部门店写入 Redis GEO（幂等：先删 key 再灌） */
    @Override
    public void run(ApplicationArguments args) {
        loadGeo();
    }

    @Override
    public List<StoreVO> nearby(String keyword) {
        // ===== 第 1 步：GEOSEARCH。以用户位置为圆心，50km 内按距离升序，结果带距离 =====
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                RedisKeyConstants.STORE_GEO,
                GeoReference.fromCoordinate(new Point(USER_LNG, USER_LAT)),
                new Distance(SEARCH_RADIUS_KM, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(SEARCH_LIMIT));

        // Map<Long, Double> 存"门店 id → 距离公里"。★用 LinkedHashMap 保留 GEO 返回的距离升序，
        // 后面即使再排一次，语义也不会变
        Map<Long, Double> distanceMap = new HashMap<>();
        if (results != null) {
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                Long storeId = Long.valueOf(result.getContent().getName());
                double km = result.getDistance().getValue();
                // 距离只保留一位小数：Redis 给的是 double，原样传前端会显示 1.8000000003km
                distanceMap.put(storeId, Math.round(km * 10) / 10.0);
            }
        }

        if (distanceMap.isEmpty()) {
            // Redis 被清空或重启过 → 自愈：重新灌载一次再查（生产可由定时任务兜底）
            log.warn("store:geo 为空，触发重新加载");
            loadGeo();
            return List.of();
        }

        // ===== 第 2 步：一次查回这批门店（★不在循环里 selectById，避免 N+1）=====
        List<Store> stores = storeMapper.selectList(new LambdaQueryWrapper<Store>()
                .in(Store::getId, distanceMap.keySet()));
        if (stores.isEmpty()) {
            return List.of();
        }

        // ===== 第 3 步：教练数与在售课程数，各自一次查回后内存计数（同样避免 N+1）=====
        List<Long> storeIds = stores.stream().map(Store::getId).toList();
        Map<Long, Integer> coachCounts = countByStore(coachMapper.selectList(
                new LambdaQueryWrapper<Coach>()
                        .in(Coach::getStoreId, storeIds)
                        .eq(Coach::getStatus, 1)), Coach::getStoreId);
        Map<Long, Integer> courseCounts = countByStore(courseStoreMapper.selectList(
                new LambdaQueryWrapper<CourseStore>()
                        .in(CourseStore::getStoreId, storeIds)
                        .eq(CourseStore::getStatus, 1)), CourseStore::getStoreId);

        // ===== 第 4 步：关键词过滤 + 组装 VO + 按距离升序 =====
        List<StoreVO> result = new ArrayList<>();
        for (Store store : stores) {
            if (!matchesKeyword(store, keyword)) {
                continue;
            }
            result.add(toVO(store, distanceMap.get(store.getId()),
                    coachCounts.getOrDefault(store.getId(), 0),
                    courseCounts.getOrDefault(store.getId(), 0)));
        }
        result.sort(Comparator.comparingDouble(StoreVO::getDistance));
        fillRatings(result);
        return result;
    }

    /**
     * 批量填门店评分摘要（一次 RPC 拿一屏门店的分数）。
     *
     * <p>★取不到只打 warn：门店列表是用户选店的第一屏，
     * 不能让"order 服务抖一下"把它变成空白页。留 null 就是"暂无评分"，
     * 和"0 分"是两种状态，前端按 null 渲染不显示星标即可。</p>
     */
    private void fillRatings(List<StoreVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (StoreVO vo : vos) {
            if (vo.getId() != null && !ids.contains(vo.getId())) {
                ids.add(vo.getId());
            }
        }
        try {
            Result<List<RatingStatsDTO>> statsResult =
                    ratingClient.stats("STORE", ids);
            if (statsResult.getCode() != 0 || statsResult.getData() == null) {
                return;
            }
            Map<Long, RatingStatsDTO> statsMap = new HashMap<>();
            for (RatingStatsDTO stat : statsResult.getData()) {
                statsMap.put(stat.getTargetId(), stat);
            }
            for (StoreVO vo : vos) {
                com.yuedong.order.api.dto.RatingStatsDTO stat = statsMap.get(vo.getId());
                if (stat != null) {
                    vo.setRatingText(stat.getAvgText());
                    vo.setRatingCount(stat.getRatingCount());
                }
            }
        } catch (Exception e) {
            log.warn("取门店评分失败，列表按\"暂无评分\"渲染: ids={}", ids, e);
        }
    }

    /**
     * 全量灌载门店坐标到 Redis GEO。
     *
     * <p>先 {@code delete(key)} 再逐家 GEOADD：这样"门店被删掉"也能在 GEO 里同步消失
     * （只 ADD 不删会留下已删除门店的坐标，GEOSEARCH 会返回它，回表查不到就表现为"少了几家店"）。
     * 门店数量很小，逐条命令的往返开销可以忽略；真要优化就换 pipeline。</p>
     */
    private void loadGeo() {
        // 只灌营业中的门店：已关闭的店不该出现在"附近门店"里
        List<Store> stores = storeMapper.selectList(new LambdaQueryWrapper<Store>()
                .eq(Store::getStatus, 1));

        redis.delete(RedisKeyConstants.STORE_GEO);
        for (Store store : stores) {
            if (store.getLng() == null || store.getLat() == null) {
                continue;   // 缺坐标的脏数据不能让 GEOADD 抛异常把整个初始化打断
            }
            redis.opsForGeo().add(RedisKeyConstants.STORE_GEO,
                    new Point(store.getLng().doubleValue(), store.getLat().doubleValue()),
                    String.valueOf(store.getId()));
        }
        log.info("门店 GEO 已灌载: 门店数={}", stores.size());
    }

    // =====================================================================
    // 管理端（fitness-admin-server 经 Feign 调用）
    // =====================================================================
    @Override
    public List<StoreDTO> adminList() {
        // 查全部门店（含已关闭），管理端要能看到并重新上架
        List<Store> stores = storeMapper.selectList(new LambdaQueryWrapper<Store>()
                .orderByAsc(Store::getId));

        List<StoreDTO> rows = new ArrayList<>();
        for (Store store : stores) {
            rows.add(toDTO(store));
        }
        return rows;
    }

    /**
     * 单店详情（内部接口用）。
     * <p>查 {@code t_store} 主键一条；门店不存在返回 null，由调用方决定怎么兜——
     * 这里不抛异常，因为"店被删了"对订单/预约来说不是错误，是历史。</p>
     */
    @Override
    public StoreDTO detail(Long storeId) {
        Store store = storeMapper.selectById(storeId);
        return store == null ? null : toDTO(store);
    }

    @Override
    public void adminSave(StoreSaveDTO dto) {
        Store store;
        boolean isNew = dto.getId() == null;
        if (isNew) {
            store = new Store();
            store.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
            store.setCreatedAt(LocalDateTime.now());
        } else {
            store = storeMapper.selectById(dto.getId());
            if (store == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "门店不存在");
            }
        }

        store.setName(dto.getName());
        store.setLng(dto.getLng());
        store.setLat(dto.getLat());
        store.setAddress(dto.getAddress());
        store.setIntro(dto.getIntro());
        store.setTags(dto.getTags());
        store.setImage(dto.getImage());
        store.setOpenStartHour(dto.getOpenStartHour() == null ? 7 : dto.getOpenStartHour());
        store.setOpenEndHour(dto.getOpenEndHour() == null ? 23 : dto.getOpenEndHour());
        store.setIs24h(dto.getIs24h() == null ? 0 : dto.getIs24h());
        store.setOpenHoursText(dto.getOpenHoursText());
        store.setPhone(dto.getPhone());
        if (!isNew && dto.getStatus() != null) {
            store.setStatus(dto.getStatus());
        }

        // ★可约时段依赖这两个整点，配反了会让时段接口返回空列表（而不是报错），
        // 所以必须在写入前判掉，并把原因写在异常信息里
        if (store.getIs24h() == 0 && store.getOpenEndHour() <= store.getOpenStartHour()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "营业结束整点必须晚于开始整点");
        }
        store.setUpdatedAt(LocalDateTime.now());

        if (isNew) {
            storeMapper.insert(store);
        } else {
            storeMapper.updateById(store);
        }

        // 经纬度变了就必须重建 GEO：GEO 里没有"更新坐标"的独立操作，
        // 重新灌载一次最简单，代价也最小
        loadGeo();
        log.info("管理端保存门店: id={}, name={}, 新增={}", store.getId(), store.getName(), isNew);
    }

    /**
     * 管理端“删除门店”实际做的是<b>停用</b>（status=0）。
     *
     * <p>★为什么不再物理删除：历史订单、凭证、预约里都存了 store_id，
     * 物理删除会让它们指向一个查不到的门店；而且门店是“有历史引用”的实体，
     * 下架比删除更符合业务。停用后：</p>
     * <ul>
     *   <li>课程可售判断会因 {@code store.status != 1} 返回不可售；</li>
     *   <li>课程×门店关系同步置 0，避免详情页仍显示“在售”；</li>
     *   <li>附近门店 GEO 里移除坐标，不再被搜到。</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adminDelete(Long id) {
        Store store = storeMapper.selectById(id);
        if (store == null) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();

        // 改：门店 status=0（停用），保留行给历史订单/预约引用
        store.setStatus(0);
        store.setUpdatedAt(now);
        storeMapper.updateById(store);

        // 改：同步停用这家店的所有课程关系，并清掉对应课程详情缓存。
        // 只改 store 不改关系的话，课程详情里“这家店在卖”的旧缓存可能还会留 30 分钟。
        List<CourseStore> relations = courseStoreMapper.selectList(
                new LambdaQueryWrapper<CourseStore>().eq(CourseStore::getStoreId, id));
        for (CourseStore relation : relations) {
            if (relation.getStatus() == null || relation.getStatus() != 0) {
                relation.setStatus(0);
                relation.setUpdatedAt(now);
                courseStoreMapper.updateById(relation);
            }
            // 缓存删除失败不能回滚门店停用：记日志，等 30 分钟自然过期
            try {
                redis.delete(RedisKeyConstants.courseDetail(relation.getCourseId()));
            } catch (Exception e) {
                log.error("门店停用后课程缓存删除失败，等待过期: courseId={}", relation.getCourseId(), e);
            }
        }

        // 从 GEO 里移除该店坐标，否则附近门店会返回一个已停用的 id。
        // GEO 同样只是缓存，失败不应回滚 DB 的停用操作。
        try {
            redis.opsForGeo().remove(RedisKeyConstants.STORE_GEO, String.valueOf(id));
        } catch (Exception e) {
            log.error("门店已停用，但 GEO 坐标移除失败，等待下次启动重建: storeId={}", id, e);
        }
        log.info("管理端停用门店: id={}, 同步停用课程关系 {} 条", id, relations.size());
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 关键词匹配：名称 / 地址 / 标签 任一命中即可。大小写不敏感由 lower 处理 */
    private boolean matchesKeyword(Store store, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String target = keyword.trim().toLowerCase();
        String hay = (nullToEmpty(store.getName()) + nullToEmpty(store.getAddress())
                + nullToEmpty(store.getTags())).toLowerCase();
        return hay.contains(target);
    }

    /** 按 storeId 分组计数。★用泛型是为了门店/课程两类关系都能复用同一段计数逻辑 */
    private <T> Map<Long, Integer> countByStore(List<T> rows,
                                                java.util.function.Function<T, Long> storeIdGetter) {
        Map<Long, Integer> counts = new HashMap<>();
        for (T row : rows) {
            Long storeId = storeIdGetter.apply(row);
            if (storeId == null) {
                continue;
            }
            Integer current = counts.get(storeId);
            counts.put(storeId, current == null ? 1 : current + 1);
        }
        return counts;
    }

    private StoreVO toVO(Store store, Double distance, int coachCount, int courseCount) {
        List<String> tags = new ArrayList<>();
        if (store.getTags() != null && !store.getTags().isBlank()) {
            for (String tag : store.getTags().split(",")) {
                if (!tag.isBlank()) {
                    tags.add(tag.trim());
                }
            }
        }
        // 距离保留一位小数后再拼展示串，避免前端出现 1.8000000003km
        String distanceText = distance == null ? null : String.format("%.1fkm", distance);

        return StoreVO.builder()
                .id(store.getId())
                .name(store.getName())
                .distance(distance)
                .distanceText(distanceText)
                .address(store.getAddress())
                .image(store.getImage())
                // ratingText/ratingCount 不在这里填：评分在 yd_order，由 nearby() 末尾
                // 一次批量 RPC 统一补（★组装函数保持纯粹，跨服务取数集中一处好排查）
                .tags(tags)
                .openHoursText(store.getOpenHoursText())
                .phone(store.getPhone())
                .coachCount(coachCount)
                .courseCount(courseCount)
                .build();
    }

    /** 实体 → 管理端 DTO。★管理端要能看到计算列（营业整点），C 端只给文案 */
    private StoreDTO toDTO(Store store) {
        return StoreDTO.builder()
                .id(store.getId())
                .name(store.getName())
                .lng(store.getLng())
                .lat(store.getLat())
                .address(store.getAddress())
                .intro(store.getIntro())
                .tags(store.getTags())
                .image(store.getImage())
                .openStartHour(store.getOpenStartHour())
                .openEndHour(store.getOpenEndHour())
                .is24h(store.getIs24h())
                .openHoursText(store.getOpenHoursText())
                .phone(store.getPhone())
                .status(store.getStatus())
                .build();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
