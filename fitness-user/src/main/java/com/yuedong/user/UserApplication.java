package com.yuedong.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户服务启动类。
 *
 * <p>@MapperScan：扫描 mapper 包，让 MyBatis-Plus 为接口生成代理实现并注入容器。</p>
 */
@EnableDiscoveryClient
@MapperScan("com.yuedong.user.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong") // 同时扫描 com.yuedong.common（全局异常处理器）
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
