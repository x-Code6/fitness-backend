package com.yuedong.course.cache;

import com.yuedong.common.constant.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 课程详情缓存失效入口。
 *
 * <p>秒杀活动状态变化、后台提前结束时都要让详情缓存失效，
 * 统一放在这里，避免 service 与 task 各写一份删除逻辑。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseDetailCacheEvictor {

    private final StringRedisTemplate redis;

    /** 删课程详情缓存；失败只记日志，等自然过期 */
    public void evict(Long courseId) {
        if (courseId == null) {
            return;
        }
        try {
            redis.delete(RedisKeyConstants.courseDetail(courseId));
        } catch (Exception e) {
            log.error("课程详情缓存删除失败，等待过期: courseId={}", courseId, e);
        }
    }
}
