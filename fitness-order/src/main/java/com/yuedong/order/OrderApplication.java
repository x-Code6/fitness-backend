package com.yuedong.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 订单服务启动类（第③④步实现：预约/状态机/延迟取消；秒杀 Lua+MQ+轮询）。
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.yuedong.course.api")  // 按需只引入课程域的 Feign 契约
@MapperScan("com.yuedong.order.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
