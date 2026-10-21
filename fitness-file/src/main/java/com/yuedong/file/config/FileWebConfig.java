package com.yuedong.file.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 文件服务 Web 配置：把磁盘上传根目录挂成 HTTP 静态资源。
 *
 * <p>★用 Spring 的资源处理器而不是自己写一个返回 byte[] 的下载接口：
 * 资源处理器自带 Range / Last-Modified / Content-Type 推断，
 * 而自己拼字节数组最容易犯的错是把磁盘绝对路径回显出去，
 * 或者忘了归一化让 {@code ../../} 读到别处的文件。</p>
 *
 * <p>★不提供目录列表：资源处理器只按请求路径找具体文件，
 * 目录请求不会列目录内容；这里也不额外挂目录浏览能力。
 * 换句话说，知道 UUID 文件名才能看到图，猜目录得不到任何信息。</p>
 */
@Configuration
public class FileWebConfig implements WebMvcConfigurer {

    /** 上传根目录，必须与 FileStorageService 的 file.upload-root 同源，否则"写进去读不出来" */
    @Value("${file.upload-root:./uploads}")
    private String uploadRoot;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 目录 URI 必须以 / 结尾，否则 Spring 会把它当成"单个文件"而不是目录前缀；
        // 上传目录在首次上传前可能还不存在，toUri() 此时不会自动补斜杠，所以显式补
        String location = Paths.get(uploadRoot).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
