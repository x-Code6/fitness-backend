package com.yuedong.adminserver.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 操作日志行视图（管理端日志查询页）。
 */
@Data
@Builder
public class OperateLogVO {

    private String adminName;

    private String module;

    private String operation;

    /** 方法入参（JSON 截断） */
    private String params;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 1=成功 0=失败 */
    private Integer success;

    /** yyyy-MM-dd HH:mm */
    private String time;
}
