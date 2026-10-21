package com.yuedong.file.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 上传结果：三个值各有用途，不能只给一个。
 *
 * <p>{@code path} 是相对路径（{@code category/uuid.ext}），给业务表落库；
 * {@code url} 是可访问路径（{@code /uploads/category/uuid.ext}），给前端展示。
 * 两者分开是为了"换域名/上 CDN 时只改访问前缀，库里的相对路径不用动"。</p>
 *
 * <p>{@code id} 是 t_file 主键，后续业务表如果要引用文件元数据（审计、删除）用它。</p>
 */
@Data
@AllArgsConstructor
public class UploadVO {

    /** t_file 主键 */
    private Long id;

    /** 相对路径，形如 {@code course/9f1c...a1.jpg} */
    private String path;

    /** 浏览器可直接访问的路径，形如 {@code /uploads/course/9f1c...a1.jpg} */
    private String url;
}
