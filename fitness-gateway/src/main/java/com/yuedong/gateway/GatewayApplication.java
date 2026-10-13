package com.yuedong.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关启动类。
 *
 * <p>@EnableDiscoveryClient：注册到 Nacos，同时能从 Nacos 拉取服务列表，
 * 路由配置里的 lb://fitness-user 才能按服务名负载均衡转发。</p>
 */
@EnableDiscoveryClient
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
