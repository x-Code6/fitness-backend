package com.yuedong.adminserver.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 上传结果：两个值各有用途，不能只给一个。
 *
 * <p>★{@code path}（相对路径）是给<b>落库</b>用的 —— {@code t_course.cover_image}
 * 这类列存的就是它；{@code url}（可访问路径）是给<b>前端展示</b>用的。
 * 分开给的原因很实际：<b>换域名、上 CDN、走 Nginx 的时候只改访问前缀，
 * 库里那些相对路径不用动</b>。如果把带域名的完整 URL 存进库，
 * 换一次域名就得刷全表。</p>
 */
@Data
@AllArgsConstructor
public class UploadVO {

    /** 相对路径，形如 {@code course/9f1c...a1.jpg}，落库用 */
    private String path;

    /** 浏览器可直接访问的路径，形如 {@code /uploads/course/9f1c...a1.jpg} */
    private String url;
}
