package com.yuedong.community;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 社区服务启动类：动态（三档可见性）/ Feed 分发 / 点赞 / 收藏 / 两级评论 / 浏览量刷库。
 *
 * <p>★{@code @EnableScheduling} 是本轮加上的：{@code PostViewFlushTask} 要把
 * Redis 里的浏览增量刷进 {@code t_post.view_count}。这个任务是<b>单实例</b>前提的
 * （两个实例同时读同一份 Hash 快照会重复入账），详见那个类的注释。</p>
 */
@EnableDiscoveryClient
@EnableScheduling
@EnableFeignClients(basePackages = "com.yuedong.user.api")  // 按需只引入用户域的 Feign 契约
@MapperScan("com.yuedong.community.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class CommunityApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommunityApplication.class, args);
    }
}
