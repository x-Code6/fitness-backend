package com.yuedong.common.exception;

import com.yuedong.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：抛出后由 GlobalExceptionHandler 统一转成 Result 结构返回。
 *
 * <p>用法：{@code throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);}</p>
 * <p>需要更具体的文案时：{@code throw new BusinessException(ErrorCode.PARAM_ERROR, "昵称过长");}</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码（对应统一响应里的 code） */
    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}
