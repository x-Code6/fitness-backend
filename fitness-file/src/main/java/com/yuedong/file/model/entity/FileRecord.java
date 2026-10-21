package com.yuedong.file.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件元数据（PO），对应表 yd_file.t_file。
 *
 * <p>★为什么除了磁盘文件还要存一张表：磁盘只能证明"有这么个文件"，
 * 证明不了"谁在什么时候传的、属于哪个业务域"。没有这张表，
 * 管理端无法分页审计上传记录，将来清理孤儿文件、按业务域统计容量也无从下手。</p>
 *
 * <p>★表里同时存 path（相对路径）与 url（访问路径）：path 是给业务表落库的稳定值，
 * url 是给前端直接展示的值。将来换域名/CDN 只改前缀，业务表里的 path 不用动。</p>
 */
@Data
@TableName("t_file")
public class FileRecord {

    /** 主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务分类（落盘子目录）：course/store/coach/gift/video/community/banner/avatar/post/common */
    @TableField("biz_category")
    private String bizCategory;

    /** 相对路径，形如 {@code course/9f1c...a1.jpg}，业务表落库用 */
    @TableField("file_path")
    private String filePath;

    /** 可访问路径，形如 {@code /uploads/course/9f1c...a1.jpg}，前端展示用 */
    @TableField("file_url")
    private String fileUrl;

    /** multipart 上报的 Content-Type（浏览器可伪造，只作参考，不用于安全判断） */
    @TableField("content_type")
    private String contentType;

    /** 文件字节数 */
    @TableField("size_bytes")
    private Long sizeBytes;

    /** 上传者类型：ADMIN 管理端 / USER 用户端 */
    @TableField("uploader_type")
    private String uploaderType;

    /** 上传者 id（管理员 id 或用户 id）。跨库只存 id，不建外键 */
    @TableField("uploader_id")
    private Long uploaderId;

    /** 上传时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
