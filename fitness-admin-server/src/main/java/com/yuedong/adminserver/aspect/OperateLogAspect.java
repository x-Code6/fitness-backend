package com.yuedong.adminserver.aspect;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.adminserver.context.AdminContext;
import com.yuedong.adminserver.mapper.OperateLogMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 操作日志切面：拦截所有标注 @OperateLog 的管理端写操作，异步落库。
 *
 * <p>记录内容：操作人 / 模块 / 操作 / 方法 / 参数（JSON 截断）/ IP / 耗时 / 是否成功。
 * 异步执行（独立线程池）保证日志故障不影响主流程。</p>
 *
 * <p>★线程池有界（1~2 线程 + 1000 队列），队列满计数丢弃，
 * 不能像无界队列那样在日志洪峰时无限堆积。</p>
 *
 * <p>★{@code AdminContext} 和 {@code RequestContextHolder} 都是 ThreadLocal，
 * 只属于请求线程；操作人 / IP / 方法 / 参数必须在<b>提交异步任务前</b>取好，
 * 到日志线程里再读只会得到 null。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperateLogAspect {

    private static final int LOG_QUEUE_CAPACITY = 1000;

    private final OperateLogMapper operateLogMapper;

    /** 队列满时累计丢弃条数，只做低频告警，避免丢弃时刷日志 */
    private final AtomicLong droppedLogs = new AtomicLong();

    /** 日志落库线程池：有界队列 + 守护线程 + 自定义丢弃策略，主流程优先 */
    private final ExecutorService logExecutor = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(LOG_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "operate-log");
                t.setDaemon(true);
                return t;
            },
            (task, executor) -> {
                long dropped = droppedLogs.incrementAndGet();
                if (dropped == 1 || dropped % 100 == 0) {
                    log.warn("操作日志队列已满，累计丢弃 {} 条", dropped);
                }
            }
    );

    @Around("@annotation(operateLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperateLog operateLog) {
        long start = System.currentTimeMillis();
        Throwable error = null;
        Object result = null;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            error = e;
        }
        long cost = System.currentTimeMillis() - start;
        boolean ok = error == null;

        // ★必须在请求线程里取：AdminContext / RequestContextHolder 都是 ThreadLocal，
        //   到日志线程再读只会得到 null（admin_id=0、ip=null 的根源）
        Long adminId = AdminContext.getAdminId();
        String adminName = AdminContext.getAdminName();
        String ip = currentIp();
        String method = joinPoint.getSignature().toShortString();
        String params = truncate(Arrays.toString(joinPoint.getArgs()), 480);

        // 异步落库：主流程不等日志写入；提交失败也不能影响业务方法
        try {
            logExecutor.submit(() -> saveLog(operateLog, method, params, cost, ok, adminId, adminName, ip));
        } catch (Exception e) {
            log.warn("操作日志提交失败，已忽略", e);
        }
        if (error instanceof RuntimeException re) {
            throw re;   // 业务异常原样抛出（全局异常处理器统一转 Result）
        }
        if (error != null) {
            throw new RuntimeException(error); // 受检异常包装后抛出
        }
        return result;
    }

    /** 组装并落库一条操作日志（参数全部由请求线程捕获后传入） */
    private void saveLog(OperateLog operateLog, String method, String params,
                         long cost, boolean success, Long adminId, String adminName, String ip) {
        try {
            // 注意：core.OperateLog 是注解，entity.OperateLog 是实体（同名），实体用全限定名
            com.yuedong.adminserver.model.entity.OperateLog row =
                    new com.yuedong.adminserver.model.entity.OperateLog();
            row.setAdminId(adminId == null ? 0 : adminId);
            row.setAdminName(adminName == null ? "未知" : adminName);
            row.setModule(operateLog.module());
            row.setOperation(operateLog.operation());
            row.setMethod(method);
            row.setParams(params);
            row.setIp(ip);
            row.setDurationMs(cost);
            row.setSuccess(success ? 1 : 0);
            row.setCreatedAt(LocalDateTime.now());
            operateLogMapper.insert(row);
        } catch (Exception e) {
            log.error("操作日志落库失败", e);
        }
    }

    /** 应用关闭时先让队列里的日志落完，超时再强制中断，避免线程泄漏 */
    @PreDestroy
    public void shutdown() {
        logExecutor.shutdown();
        try {
            if (!logExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                logExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logExecutor.shutdownNow();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String xff = request.getHeader("X-Forwarded-For");
            return xff != null ? xff.split(",")[0].trim() : request.getRemoteAddr();
        }
        return null;
    }
}
