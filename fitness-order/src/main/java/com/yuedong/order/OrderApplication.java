package com.yuedong.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单服务启动类（购买流水 + 权益凭证 + 私教预约 + 时段占用 + 购物车 + 评价）。
 *
 * <p>★{@code @EnableScheduling} 是本轮加上的：本服务承担三个定时任务
 * （凭证过期退款、预约到点扣次、排行榜归档）。它们全部设计成"重跑安全"
 * （靠带状态条件的 CAS，不靠锁），所以单机跑和两实例同时跑结果一样。</p>
 */
@EnableDiscoveryClient
@EnableScheduling
@EnableFeignClients(basePackages = "com.yuedong.course.api")  // 按需只引入课程域的 Feign 契约
@MapperScan("com.yuedong.order.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
