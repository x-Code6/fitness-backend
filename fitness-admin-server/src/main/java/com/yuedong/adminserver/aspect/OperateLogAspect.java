package com.yuedong.adminserver.aspect;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.adminserver.context.AdminContext;

import com.yuedong.adminserver.mapper.OperateLogMapper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 操作日志切面：拦截所有标注 @OperateLog 的管理端写操作，异步落库。
 *
 * <p>记录内容：操作人 / 模块 / 操作 / 方法 / 参数（JSON 截断）/ IP / 耗时 / 是否成功。
 * 异步执行（独立线程池）保证日志故障不影响主流程。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperateLogAspect {

    private final OperateLogMapper operateLogMapper;

    /** 日志落库线程池（守护性质，队列满丢弃，主流程优先） */
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "operate-log");
        t.setDaemon(true);
        return t;
    });

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
        // 异步落库：主流程不等日志写入
        logExecutor.submit(() -> saveLog(joinPoint, operateLog, cost, ok));
        if (error instanceof RuntimeException re) {
            throw re;   // 业务异常原样抛出（全局异常处理器统一转 Result）
        }
        if (error != null) {
            throw new RuntimeException(error); // 受检异常包装后抛出
        }
        return result;
    }

    /** 组装并落库一条操作日志 */
    private void saveLog(ProceedingJoinPoint joinPoint, OperateLog operateLog,
                         long cost, boolean success) {
        try {
            // 注意：core.OperateLog 是注解，entity.OperateLog 是实体（同名），实体用全限定名
            com.yuedong.adminserver.model.entity.OperateLog row = new com.yuedong.adminserver.model.entity.OperateLog();
            row.setAdminId(AdminContext.getAdminId() == null ? 0 : AdminContext.getAdminId());
            row.setAdminName(AdminContext.getAdminName() == null ? "未知" : AdminContext.getAdminName());
            row.setModule(operateLog.module());
            row.setOperation(operateLog.operation());
            row.setMethod(joinPoint.getSignature().toShortString());
            row.setParams(truncate(Arrays.toString(joinPoint.getArgs()), 480));
            row.setIp(currentIp());
            row.setDurationMs(cost);
            row.setSuccess(success ? 1 : 0);
            row.setCreatedAt(LocalDateTime.now());
            operateLogMapper.insert(row);
        } catch (Exception e) {
            log.error("操作日志落库失败", e);
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
