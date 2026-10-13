package com.yuedong.common.idempotent;

import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * {@link Idempotent} 注解的执行切面。
 *
 * <p>整体逻辑只有三步：拼 key → SETNX 抢位 → 抢不到就拒绝。
 * 之所以不需要分布式锁、不需要 Lua：<b>"窗口期内同一个用户同一个接口只放过一次"这件事，
 * SETNX 一条命令就是原子的</b>，抢到的人继续执行，没抢到的人直接吃一个业务异常。</p>
 *
 * <p>这个 Bean 不在这里标 {@code @Component}，而是由 {@link IdempotentConfig} 用
 * {@code @Bean} 注册——因为要靠"类路径上有没有 Redis、当前是不是 Servlet Web 应用"
 * 来决定装不装配。放在组件扫描里会被无条件创建，网关（WebFlux、无 Redis）就会启动失败。</p>
 */
@Slf4j
@Aspect
public class IdempotentAspect {

    /** 兜底窗口：注解里写了 0 或负数时用它，避免 key 永不过期把用户永久锁死 */
    private static final int MIN_EXPIRE_SECOND = 1;

    private final StringRedisTemplate redisTemplate;

    public IdempotentAspect(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 环绕通知：命中 {@code @Idempotent} 的方法先进这里。
     *
     * @param joinPoint   被拦截的方法调用点
     * @param idempotent  Spring/AspectJ 会把方法上的注解实例直接注入进来，省掉反射取注解
     * @return 目标方法的原始返回值
     */
    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {

        // ===== 第 1 步：拼防重 key =====
        // 格式 idempotent:api:{业务前缀}:{用户id}:{请求路径}，三段各解决一个问题：
        //   前缀   → 同一个接口上不同动作不互相干扰
        //   用户id → 防重是"每个用户独立"的，不能张三点了让李四也被拦
        //   路径   → 不同接口互不影响
        String redisKey = buildKey(joinPoint, idempotent);

        // ===== 第 2 步：SETNX 抢位 =====
        // setIfAbsent(key, value, timeout) = Redis 的 SET key value NX EX seconds，
        // "判存在"和"写入"是同一条命令，所以并发下只有一个请求能拿到 true。
        Duration window = Duration.ofSeconds(Math.max(MIN_EXPIRE_SECOND, idempotent.expireSecond()));
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", window);

        // ===== 第 3 步：没抢到 = 窗口期内已经提交过一次，直接拒绝，不进业务方法 =====
        if (!Boolean.TRUE.equals(acquired)) {
            String tip = idempotent.message().isBlank()
                    ? ErrorCode.REPEAT_SUBMIT.getMessage()
                    : idempotent.message();
            log.warn("重复提交被拦截: key={}", redisKey);
            throw new BusinessException(ErrorCode.REPEAT_SUBMIT, tip);
        }

        // ===== 第 4 步：业务执行；一旦失败要把位放开 =====
        // 这一步很容易漏。如果业务抛异常却不删 key，用户"改了参数再提交一次"
        // 会在窗口期内一直被拦，表现为"页面报错后我怎么点都没反应"。
        // 放开后最坏结果是重复请求再次进入业务方法——业务自己的校验仍在，不会因此产生脏数据。
        try {
            return joinPoint.proceed();
        } catch (Throwable businessError) {
            redisTemplate.delete(redisKey);
            throw businessError;
        }
    }

    /**
     * 组装防重 key。
     *
     * <p>前缀留空时取方法名（如 {@code receive}），可读且不要求每个注解都写一遍字符串。</p>
     */
    private String buildKey(ProceedingJoinPoint joinPoint, Idempotent idempotent) {

        // 注解没写前缀 → 用被拦截的方法名兜底
        String prefix = idempotent.prefix();
        if (prefix.isBlank()) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            prefix = signature.getMethod().getName();
        }

        // 当前用户 id 由各服务注册的 UserContextInterceptor 从请求头 X-User-Id 放进 ThreadLocal。
        // 取不到说明这个接口是匿名可访问的（网关白名单里的那些），而匿名接口不该用本注解
        // ——没有用户维度就无法界定"重复提交"，所以直接快速失败让开发者发现问题，
        // 而不是悄悄拼出一个 "null" 让所有匿名用户共用一把锁。
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalStateException("@Idempotent 不能用在未登录接口上："
                    + joinPoint.getSignature().getDeclaringTypeName() + "#" + prefix
                    + "（UserContext 里没有 userId，请检查该路径是否在网关白名单中）");
        }

        // 请求路径：从当前线程绑定的 ServletRequestAttributes 上拿。
        // Spring MVC 是"一个请求一个线程"，所以这里能拿到；WebFlux 网关不走这个切面。
        HttpServletRequest request = currentRequest();
        String path = request == null ? prefix : request.getRequestURI();

        return RedisKeyConstants.idempotentApi(prefix, userId, path);
    }

    /** 取当前线程绑定的 HTTP 请求；取不到返回 null，由调用方兜底 */
    private HttpServletRequest currentRequest() {
        Object attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }
}
