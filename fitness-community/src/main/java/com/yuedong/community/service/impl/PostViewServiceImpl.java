package com.yuedong.community.service.impl;

import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.community.mapper.PostMapper;
import com.yuedong.community.mq.PostViewPublisher;
import com.yuedong.community.service.PostViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 浏览量实现：Redis 存增量、DB 存账本、Kafka 存明细。
 *
 * <p>★读一次浏览要落三处，但<b>只有 Redis 在请求路径上</b>：
 * 一次 SADD（去重）+ 一次 HINCRBY（增量）+ 一条 Kafka send（异步、不等待）。
 * DB 那一步交给定时任务批量做，所以"看一条动态"不会因为一次 UPDATE 而变慢，
 * 也不会因为刷库失败而丢数据——增量还在 Redis 里。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostViewServiceImpl implements PostViewService {

    /** 去重键的日期格式：post:viewed:{yyyyMMdd} 的 key 后缀，也是 eventId 的一部分 */
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 去重集合的存活时间。★3 天而不是 25 小时：
     * 跨零点时"先写 key 再设 TTL"之间有间隙，机器时钟也可能略偏，
     * 只给 25 小时会让边界时刻的浏览"今天永远计不上"。多留两天只是多占两天内存。
     */
    private static final Duration DEDUPE_TTL = Duration.ofDays(3);

    private final StringRedisTemplate redis;
    private final PostMapper postMapper;
    private final PostViewPublisher postViewPublisher;

    @Override
    public boolean countView(Long postId, Long userId) {
        // ===== 第 1 步：没登录不计（去重要靠人，匿名没有稳定身份） =====
        if (userId == null) {
            return false;
        }

        // ===== 第 2 步：去重。SADD 返回 1 才是"今天第一次看这条" =====
        // ★为什么不先 SISMEMBER 再 SADD：两次往返之间并发请求会都判成"没看过"，
        //   于是计两票。SADD 本身是原子的，返回值就是判定结果，一次调用做完两件事。
        String day = LocalDate.now().format(DAY_FMT);
        String dedupeKey = RedisKeyConstants.postViewed(day);
        Long added = redis.opsForSet().add(dedupeKey, postId + ":" + userId);
        if (added == null || added == 0) {
            return false;
        }
        // 每天一个 key，所以 TTL 只需在"今天第一次写"时设一次（上面已确认是第一次）
        redis.expire(dedupeKey, DEDUPE_TTL);

        // ===== 第 3 步：增量进 Redis（不碰 DB） =====
        redis.opsForHash().increment(RedisKeyConstants.POST_VIEW_DELTA, String.valueOf(postId), 1);

        // ===== 第 4 步：明细进 Kafka（复用 behavior 通道，失败不影响浏览） =====
        postViewPublisher.publish("postview:" + postId + ":" + userId + ":" + day, postId, userId);
        return true;
    }

    @Override
    public int currentDelta(Long postId) {
        Object value = redis.opsForHash().get(RedisKeyConstants.POST_VIEW_DELTA, String.valueOf(postId));
        return parseInt(value);
    }

    @Override
    public Map<Long, Integer> currentDeltas(Collection<Long> postIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        // ★先固定成 List：下面要按同一个顺序走两遍（一遍发 HMGET、一遍贴回结果），
        //   List.copyOf 保留传入集合的顺序，又保证两次迭代顺序一定一致
        List<Long> orderedIds = List.copyOf(postIds);
        // ★一次 HMGET 拿完整页：放进循环里就是"每条动态问一次 Redis"，
        //   网络往返次数比结果本身还贵。
        //   （fields 声明成 List<Object> 是 Spring Data 的泛型形状要求：
        //     StringRedisTemplate 的 HashOperations 键类型是 Object，传 List<String> 编译不过）
        List<Object> fields = orderedIds.stream().map(id -> (Object) String.valueOf(id)).toList();
        List<Object> values = redis.opsForHash().multiGet(RedisKeyConstants.POST_VIEW_DELTA, fields);

        for (int i = 0; i < orderedIds.size(); i++) {
            result.put(orderedIds.get(i), parseInt(values.get(i)));
        }
        return result;
    }

    @Override
    public int flushToDb() {
        // ===== 第 1 步：取快照。entries 读的是这一瞬间的全量 field，读完之后只会变大不会变小 =====
        Map<Object, Object> snapshot = redis.opsForHash().entries(RedisKeyConstants.POST_VIEW_DELTA);
        if (snapshot.isEmpty()) {
            return 0;
        }

        int flushed = 0;
        for (Map.Entry<Object, Object> entry : snapshot.entrySet()) {
            String field = String.valueOf(entry.getKey());
            int delta = parseInt(entry.getValue());
            if (delta <= 0) {
                continue;
            }

            // ===== 第 2 步：★用 HINCRBY -delta 原子取出，不用 GET→DEL =====
            // "先读、写库、再删"中间新产生的浏览会被 DEL 掉，永久少计；
            // 减掉自己读到的那一份，读完之后新加的自然留在 Hash 里等下一轮。
            // 只有本机会跑这个任务（@Scheduled 单线程），不会有两个实例同时减，
            // 所以值不会被减成负数。
            redis.opsForHash().increment(RedisKeyConstants.POST_VIEW_DELTA, field, -delta);

            // ===== 第 3 步：★入账必须是 view_count + delta，不能 = delta，也不能 = Redis 的值 =====
            // 前者会把上一轮已落库的量覆盖掉，后者会被重复刷入。
            postMapper.addViewCount(Long.valueOf(field), delta);
            flushed++;
        }
        return flushed;
    }

    /** Redis 里的值可能是 null（没浏览过）或非法字符串（脏数据），统一按 0 处理 */
    private int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("浏览量增量是脏数据，按 0 处理: {}", value);
            return 0;
        }
    }
}
