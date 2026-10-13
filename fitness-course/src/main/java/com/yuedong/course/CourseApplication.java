package com.yuedong.course;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 课程服务启动类。
 *
 * <p>职责：课程列表/详情（缓存三件套）、门店 GEO、秒杀活动与库存预热、跟练视频（完课事件）。</p>
 *
 * <p>@EnableScheduling：开启定时任务，秒杀活动的"到点预热库存/到点结束"由
 * SeckillActivityService 里的 @Scheduled 方法周期扫描完成。</p>
 */
@EnableScheduling
@EnableDiscoveryClient
@MapperScan("com.yuedong.course.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class CourseApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourseApplication.class, args);
    }
}
