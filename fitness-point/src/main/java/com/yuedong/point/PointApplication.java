package com.yuedong.point;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 积分服务启动类（第⑤步实现：Bitmap 打卡、积分流水、ZSet 排行榜、完课事件消费）。
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.yuedong.user.api")  // 按需只引入用户域的 Feign 契约
@MapperScan("com.yuedong.point.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class PointApplication {

    public static void main(String[] args) {
        SpringApplication.run(PointApplication.class, args);
    }
}
