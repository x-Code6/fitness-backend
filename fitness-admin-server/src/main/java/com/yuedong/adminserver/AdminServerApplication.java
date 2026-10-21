package com.yuedong.adminserver;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 管理端后端服务启动类（BFF：聚合 5 个业务服务）。
 *
 * <p>职责：管理员认证（图形验证码）/ RBAC 权限 / 操作日志，
 * 以及聚合各业务服务的内部接口，对管理端前端暴露 /admin/** 接口。</p>
 *
 * <p>@EnableAsync：操作日志异步落库（不阻塞管理操作主流程）。
 * scanBasePackages = com.yuedong：同时扫描 common（复用全局异常处理器）。
 * Feign 契约按需引入 5 个 api 模块的 client 包（不含未消费的 behavior）。</p>
 */
@EnableAsync
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {
        "com.yuedong.user.api.client",
        "com.yuedong.course.api.client",
        "com.yuedong.order.api.client",
        "com.yuedong.point.api.client",
        "com.yuedong.community.api.client"
})
@MapperScan("com.yuedong.adminserver.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class AdminServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminServerApplication.class, args);
    }
}
