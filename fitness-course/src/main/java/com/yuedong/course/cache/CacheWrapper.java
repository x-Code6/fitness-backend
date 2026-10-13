package com.yuedong.course.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 逻辑过期缓存包装器。
 *
 * <p>把业务数据 + 逻辑过期时间一起序列化进 Redis；Redis 层面"永不过期"，
 * 过期与否由业务代码判断 —— 这样过期后可以"返回旧数据 + 异步重建"，
 * 而不是像物理 TTL 那样删除缓存、让并发请求全部砸到数据库（击穿）。</p>
 *
 * @param <T> 缓存的业务数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheWrapper<T> {

    /** 业务数据 */
    private T data;

    /** 逻辑过期时间戳（毫秒） */
    private Long expireTime;
}
