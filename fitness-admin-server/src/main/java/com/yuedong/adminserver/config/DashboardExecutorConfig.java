package com.yuedong.adminserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 看板 BFF 并行取数线程池。
 *
 * <p>看板要聚合 order / course / user 三路只读数据，原来串行调用，
 * 总耗时是三次 Feign 之和。这里用专用线程池并行，核心线程数按“三路”定，
 * 队列有界，满了由调用线程执行（CallerRunsPolicy）做背压，而不是静默丢数据。</p>
 *
 * <p>★不要用 {@code ForkJoinPool.commonPool()}：Feign 是阻塞 I/O，
 * 会拖垮公共池，影响同一 JVM 里其他 CompletableFuture / parallelStream。</p>
 */
@Configuration
public class DashboardExecutorConfig {

    @Bean(name = "dashboardExecutor", destroyMethod = "shutdown")
    public ExecutorService dashboardExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                3, 6, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                r -> new Thread(r, "dashboard-bff-" + seq.incrementAndGet()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
