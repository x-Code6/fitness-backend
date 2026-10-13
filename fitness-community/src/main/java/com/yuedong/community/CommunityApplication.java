package com.yuedong.community;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 社区服务启动类：动态发布 / Feed 流 / 点赞 / 评论回复。
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.yuedong.user.api")  // 按需只引入用户域的 Feign 契约
@MapperScan("com.yuedong.community.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class CommunityApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityApplication.class, args);
    }
}
