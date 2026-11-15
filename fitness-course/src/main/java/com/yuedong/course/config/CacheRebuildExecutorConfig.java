package com.yuedong.course.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 缓存重建线程池（逻辑过期方案专用）。
 *
 * <p>击穿防护思路：缓存"永不过期"但内嵌逻辑过期时间；
 * 逻辑过期后第一个线程拿到互斥锁，把"查库重建缓存"丢进这个线程池异步执行，
 * 所有请求（包括拿锁线程自己）立刻返回旧数据 —— 整个过程没有任何线程被阻塞在 DB 上，
 * 高并发下不会发生"雪崩式回源"。</p>
 */
@Configuration
public class CacheRebuildExecutorConfig {

    @Bean("cacheRebuildExecutor")
    public ExecutorService cacheRebuildExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> new Thread(r, "cache-rebuild"),
                // 队列满抛 RejectedExecutionException：由 tryRebuildAsync 捕获、清理本地单飞标记并记日志。
                // ★不能继续用 DiscardPolicy：任务被静默丢弃时 finally 不会执行，单飞标记会永久卡住，
                //   这门课在本实例就再也不会触发重建了。
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
