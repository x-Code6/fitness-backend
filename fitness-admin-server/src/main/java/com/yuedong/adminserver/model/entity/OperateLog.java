package com.yuedong.adminserver.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 操作日志实体（PO），对应表 yd_admin.t_operate_log。 */
@Data
@TableName("t_operate_log")
public class OperateLog {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作人 id */
    @TableField("admin_id")
    private Long adminId;

    /** 操作人姓名快照 */
    @TableField("admin_name")
    private String adminName;

    /** 业务模块（如"课程管理"） */
    @TableField("module")
    private String module;

    /** 操作描述（如"新增课程"） */
    @TableField("operation")
    private String operation;

    /** 拦截到的方法签名 */
    @TableField("method")
    private String method;

    /** 方法入参（JSON，超长截断到 480 字符） */
    @TableField("params")
    private String params;

    /** 来源 IP（优先取 X-Forwarded-For 首段） */
    @TableField("ip")
    private String ip;

    /** 耗时（毫秒） */
    @TableField("duration_ms")
    private Long durationMs;

    /** 1 成功 / 0 失败 */
    @TableField("success")
    private Integer success;

    /** 操作时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
