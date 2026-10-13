package com.yuedong.common.exception;

import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：所有服务共用。
 *
 * <p>目标：任何异常都转成统一的 Result 结构返回，前端永远拿到 JSON，
 * 并保证异常明细只进日志、不泄露给调用方（避免暴露 SQL/堆栈信息）。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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
     * 兜底：未预期的异常。完整堆栈进日志，对外只提示"系统繁忙"。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    /** 从校验异常里取第一条字段错误的提示信息 */
    private String firstFieldMessage(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        return fieldError != null ? fieldError.getDefaultMessage() : ErrorCode.PARAM_ERROR.getMessage();
    }
}
