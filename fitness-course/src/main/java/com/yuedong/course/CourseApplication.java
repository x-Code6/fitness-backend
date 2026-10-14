package com.yuedong.course;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 课程服务启动类。
 *
 * <p>职责：课程列表/详情（缓存三件套）、门店 GEO、教练与会教范围、秒杀活动与库存预热、
 * 跟练视频（完课事件）、优惠券（含 v2 的类型/窗口/限领/范围）。</p>
 *
 * <p>@EnableScheduling：开启定时任务，秒杀活动的"到点预热库存/到点结束"由
 * SeckillActivityService 里的 @Scheduled 方法周期扫描完成。</p>
 */
@EnableScheduling
@EnableDiscoveryClient
// 按需引入两个契约域：order 的评分统计（取数失败降级为"暂无评分"）、
// user 的用户简况（D11 展示课评论要落昵称与头像配色快照，取数失败降级为默认昵称）
@EnableFeignClients(basePackages = {"com.yuedong.order.api", "com.yuedong.user.api"})
@MapperScan("com.yuedong.course.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class CourseApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourseApplication.class, args);
    }
}
