package com.yuedong.behavior;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 埋点服务启动类（第⑥步实现：埋点上报 → Kafka → 落库/热门课程聚合）。
 */
@EnableDiscoveryClient
@MapperScan("com.yuedong.behavior.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class BehaviorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BehaviorApplication.class, args);
    }
}
