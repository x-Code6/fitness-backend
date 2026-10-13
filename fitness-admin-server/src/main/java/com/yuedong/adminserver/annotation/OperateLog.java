package com.yuedong.adminserver.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解：标注在管理端写操作方法上，由 OperateLogAspect 异步记录。
 *
 * <p>用法：{@code @OperateLog(module = "课程管理", operation = "新增课程")}</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperateLog {

    /** 业务模块，如"课程管理" */
    String module();

    /** 操作描述，如"新增课程" */
    String operation();
}
