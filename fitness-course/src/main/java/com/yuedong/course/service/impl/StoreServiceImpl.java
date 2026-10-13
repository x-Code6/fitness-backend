package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.course.api.dto.StoreSaveDTO;
import com.yuedong.course.model.entity.Store;
import com.yuedong.course.mapper.StoreMapper;
import com.yuedong.course.model.vo.StoreVO;
import com.yuedong.course.service.StoreService;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店业务实现：Redis GEO 实现"附近门店按距离排序"。
 *
 * <p>链路：启动时把门店经纬度 GEOADD 进 Redis（key=store:geo，member=门店id）→
 * 查询时 GEOSEARCH（以用户坐标为圆心、按距离升序、带距离返回）→
 * 回表查门店信息 → 关键词过滤 → 组装 VO。</p>
 *
 * <p>为什么用 GEO 不用 SQL：GEO 底层是 ZSet（geohash 编码经纬度为 score），
 * "按距离排序 + 范围检索"是 O(logN) 内存操作，比 SQL 里逐行算三角函数快得多。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService, ApplicationRunner {

    /** Mock 用户位置：郑州二七广场（真实项目取用户授权定位） */
    private static final double USER_LNG = 113.660818;
    private static final double USER_LAT = 34.747307;

    private final StoreMapper storeMapper;
    private final StringRedisTemplate redis;

    /** 应用启动完成后把全部门店写入 Redis GEO（幂等：先删再灌） */
    @Override
    public void run(ApplicationArguments args) {
        loadGeo();
    }

    @Override
    public List<StoreVO> nearby(String keyword) {
        // ===== 第 1 步：GEOSEARCH 以用户位置为圆心，50km 内按距离升序返回 =====
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                RedisKeyConstants.STORE_GEO,
                GeoReference.fromCoordinate(new Point(USER_LNG, USER_LAT)),
                new Distance(50, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(20));

        List<GeoResultPair> pairs = new ArrayList<>();
        if (results != null) {
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : results) {
                String storeId = r.getContent().getName();
                double km = r.getDistance().getValue();
                pairs.add(new GeoResultPair(Long.valueOf(storeId), Math.round(km * 10) / 10.0));
            }
        }
        if (pairs.isEmpty()) {
            // Redis 被清空/重启过 → 自愈：重新灌载一次再查（生产可由定时任务兜底）
            log.warn("store:geo 为空，触发重新加载");
            loadGeo();
        }

        // ===== 第 2 步：回表查门店信息（门店表很小，一次全查避免 N+1） =====
        Map<Long, Store> storeMap = storeMapper.selectList(null).stream()
                .collect(HashMap::new, (m, s) -> m.put(s.getId(), s), HashMap::putAll);

        // ===== 第 3 步：关键词过滤 + 按距离排序 + 组装 VO =====
        return pairs.stream()
                .map(p -> {
                    Store s = storeMap.get(p.storeId());
                    if (s == null) return null;
                    String hay = s.getName() + s.getAddress() + s.getTags();
                    if (keyword != null && !keyword.isBlank() && !hay.contains(keyword.trim())) {
                        return null;
                    }
                    return StoreVO.builder()
                            .id(s.getId())
                            .name(s.getName())
                            .distance(p.distance())
                            .address(s.getAddress())
                            .rating(s.getRating())
                            .tags(s.getTags() == null ? List.of() : List.of(s.getTags().split(",")))
                            .openHours(s.getOpenHours())
                            .phone(s.getPhone())
                            .coaches(s.getCoaches())
                            .build();
                })
                .filter(v -> v != null)
                .sorted(Comparator.comparingDouble(StoreVO::getDistance))
                .toList();
    }

    /** 全量灌载门店坐标到 Redis GEO（先删 key 保证幂等） */
    private void loadGeo() {
        List<Store> stores = storeMapper.selectList(null);
        if (stores.isEmpty()) return;
        redis.delete(RedisKeyConstants.STORE_GEO);
        stores.forEach(s -> redis.opsForGeo().add(
                RedisKeyConstants.STORE_GEO, new Point(s.getLng().doubleValue(), s.getLat().doubleValue()),
                String.valueOf(s.getId())));
        log.info("门店 GEO 加载完成: {} 家", stores.size());
    }

    // =====================================================================
    // 管理端（fitness-admin-server Feign 调用）
    // =====================================================================
    @Override
    public List<StoreDTO> adminList() {
        return storeMapper.selectList(null).stream()
                .map(s -> StoreDTO.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .lng(s.getLng())
                        .lat(s.getLat())
                        .address(s.getAddress())
                        .rating(s.getRating())
                        .tags(s.getTags())
                        .openHours(s.getOpenHours())
                        .phone(s.getPhone())
                        .coaches(s.getCoaches())
                        .build())
                .toList();
    }

    @Override
    public void adminSave(StoreSaveDTO dto) {
        Store store = new Store();
        store.setId(dto.getId());
        store.setName(dto.getName());
        store.setLng(dto.getLng());
        store.setLat(dto.getLat());
        store.setAddress(dto.getAddress());
        store.setRating(dto.getRating());
        store.setTags(dto.getTags());
        store.setOpenHours(dto.getOpenHours());
        store.setPhone(dto.getPhone());
        store.setCoaches(dto.getCoaches());
        if (store.getId() != null) {
            storeMapper.updateById(store); // 编辑
        } else {
            storeMapper.insert(store);     // 新增
        }
        loadGeo(); // 门店坐标变化后重建 GEO（门店量小，全量灌载最简单可靠）
    }

    @Override
    public void adminDelete(Long id) {
        storeMapper.deleteById(id);
        redis.opsForGeo().remove(RedisKeyConstants.STORE_GEO, String.valueOf(id));
    }

    /** GEO 结果对：门店 id + 距离 */
    private record GeoResultPair(Long storeId, double distance) {
    }
}
