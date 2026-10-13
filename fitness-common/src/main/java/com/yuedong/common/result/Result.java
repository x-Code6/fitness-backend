package com.yuedong.common.result;

import lombok.Getter;

/**
 * 统一接口返回结构。
 *
 * <p>所有 REST 接口必须返回该结构，前端只需处理一种格式：
 * {@code { code: 0, message: "ok", data: ... }}，code = 0 表示业务成功。</p>
 *
 * @param <T> 业务数据类型
 */
@Getter
public class Result<T> {

    /** 业务错误码，0 表示成功 */
    private final int code;

    /** 提示信息 */
    private final String message;

    /** 业务数据，失败时为 null */
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功：携带业务数据 */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /** 成功：无业务数据 */
    public static Result<Void> ok() {
        return ok(null);
    }

    /** 失败：使用错误码默认提示 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败：错误码 + 自定义提示信息 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /** 失败：直接指定 code 与提示（全局异常处理器使用） */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
