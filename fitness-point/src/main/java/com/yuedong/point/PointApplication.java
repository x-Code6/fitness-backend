package com.yuedong.point;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 积分服务启动类（第⑤步实现：Bitmap 打卡、积分流水、ZSet 排行榜、完课事件消费）。
 *
 * <p>★{@code @EnableScheduling} 是切片 G 加上的：{@code RankArchiveTask} 每月 1 日
 * 把上个月的实时榜归档进 {@code t_rank_archive}（D17）。归档写入是 upsert，
 * 重跑安全，所以这个任务同样不需要分布式锁。</p>
 */
@EnableDiscoveryClient
@EnableScheduling
@EnableFeignClients(basePackages = "com.yuedong.user.api")  // 按需只引入用户域的 Feign 契约
@MapperScan("com.yuedong.point.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class PointApplication {

    public static void main(String[] args) {
        SpringApplication.run(PointApplication.class, args);
    }
}
