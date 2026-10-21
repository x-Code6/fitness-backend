package com.yuedong.file;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 文件服务启动类：图片上传落盘 + t_file 元数据 + /uploads/** 静态读取。
 *
 * <p>★为什么要独立成服务：上传的写入口有两类调用方（管理端后台、App 用户）。
 * 如果继续挂在 admin-server，用户上传头像/动态配图就必须先拿管理端 JWT，
 * 权限模型直接错位。独立后写入口按调用方拆开：</p>
 * <ul>
 *   <li>{@code /file/admin/**}：自己验管理端 JWT（与 admin-server 同一密钥）；</li>
 *   <li>{@code /file/user/**}：只认网关注入的 X-User-Id，用户 JWT 由网关验。</li>
 * </ul>
 *
 * <p>★{@code scanBasePackages = "com.yuedong"}：fitness-common 里的
 * GlobalExceptionHandler / CommonWebConfig 必须一起装配，统一响应与异常处理
 * 不能各服务各写一套；MyBatis Mapper 则用 @MapperScan 单独框定。</p>
 */
@EnableDiscoveryClient
@MapperScan("com.yuedong.file.mapper")
@SpringBootApplication(scanBasePackages = "com.yuedong")
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
    }
}
