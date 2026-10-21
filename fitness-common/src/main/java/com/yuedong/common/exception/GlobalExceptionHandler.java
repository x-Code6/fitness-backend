package com.yuedong.common.exception;

import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器：所有服务共用。
 *
 * <p>目标：任何异常都转成统一的 Result 结构返回，前端永远拿到 JSON，
 * 并保证异常明细只进日志、不泄露给调用方（避免暴露 SQL/堆栈信息）。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 联调开关：true 时把异常类型与原因带进对外 message。默认 false（不把内部细节给调用方） */
    @Value("${yuedong.error.verbose:false}")
    private boolean verboseError;

    /**
     * 业务异常：正常业务规则触发（如"手机号已注册"），日志级别 warn 即可。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * @RequestBody + @Valid 参数校验失败：取第一条错误提示返回。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String message = firstFieldMessage(e);
        log.warn("参数校验失败: {}", message);
        return Result.fail(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 表单绑定参数校验失败（@ModelAttribute 场景），处理方式同上。
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String message = e.getBindingResult().getFieldErrors().isEmpty()
                ? ErrorCode.PARAM_ERROR.getMessage()
                : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        log.warn("参数绑定失败: {}", message);
        return Result.fail(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 必填请求参数没传 / 传了但类型不对：<b>这是调用方的错，不是系统的错</b>。
     *
     * <p>★以前这两种都落到下面的兜底分支，对外统一是"50000 系统繁忙"。后果很实际：
     * 前端少拼一个 {@code storeId}，用户看到的是"系统繁忙，请稍后重试"，
     * 于是所有人往"后端挂了"的方向查——本次联调第一轮的排查时间几乎全花在这上面。
     * 现在给 40000 并把缺的是哪个参数名说清楚，前端日志里一眼能定位。</p>
     *
     * <p>★这两个异常类都在 spring-web（不在 spring-webmvc），所以 common 带到
     * WebFlux 的 gateway 也能正常加载，不会有 NoClassDefFoundError 的顾虑。</p>
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public Result<Void> handleMissingParam(Exception e) {
        String detail = e instanceof MissingServletRequestParameterException missing
                ? "缺少必填参数：" + missing.getParameterName()
                : "请求参数类型不正确";
        log.warn("请求参数不合法: {}", detail);
        return Result.fail(ErrorCode.PARAM_ERROR, verboseError ? detail + "（" + e.getMessage() + "）" : detail);
    }

    /**
     * 兜底：未预期的异常。完整堆栈进日志，对外只提示"系统繁忙"。
     *
     * <p>★先判"路径不存在"：Spring 6.1 在没有对应控制器方法时抛的是
     * {@code NoResourceFoundException}，它也是 Exception 的子类，以前一律落到这里，
     * 于是<b>前端写错一个接口路径，症状和真正的服务端异常一模一样</b>（都是 50000 系统繁忙）。
     * 本次联调有十几个接口报 50000，其中相当一部分其实是"路径/参数对不上"，
     * 全部被这一个兜底分支糊成了同一个错误码，白白浪费了大量排查时间。
     * 现在给它独立的 40400，一眼可辨。</p>
     *
     * <p>★为什么不直接 {@code @ExceptionHandler(NoResourceFoundException.class)}：
     * 这个类在 spring-webmvc 里，而 gateway 是 WebFlux、classpath 上没有 webmvc。
     * 本类被 common 带走，方法签名里引用一个网关侧不存在的类，会在网关那侧埋
     * NoClassDefFoundError。所以用类名判定，不引入编译期依赖。</p>
     *
     * <p>★{@code yuedong.error.verbose=true} 时把异常类型与原因带进 message，
     * 只给本地联调用（默认关闭）。开着它等于把内部实现细节告诉调用方，
     * 测试环境用完记得关。</p>
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        if (isNoHandler(e)) {
            log.warn("请求的接口不存在: {}", e.toString());
            return Result.fail(ErrorCode.NOT_FOUND, "接口不存在，请检查请求路径（" + e.getClass().getSimpleName() + "）");
        }
        if (verboseError) {
            log.error("系统异常（已回显原因，verbose 模式）", e);
            return Result.fail(ErrorCode.SYSTEM_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        log.error("系统异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    /** 是不是"没有控制器方法接这个请求"——按类名判，理由见 {@link #handleOther} */
    private boolean isNoHandler(Exception e) {
        String name = e.getClass().getSimpleName();
        return "NoResourceFoundException".equals(name) || "NoHandlerFoundException".equals(name);
    }

    /** 从校验异常里取第一条字段错误的提示信息 */
    private String firstFieldMessage(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        return fieldError != null ? fieldError.getDefaultMessage() : ErrorCode.PARAM_ERROR.getMessage();
    }
}
