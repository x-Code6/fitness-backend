package com.yuedong.file.model.vo;

import com.yuedong.file.model.entity.FileRecord;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件元数据视图（管理端分页用）。
 *
 * <p>★为什么不直接返回 FileRecord 实体：实体是数据库结构的镜像，
 * 一旦表里将来加了内部字段（如软删标记、磁盘绝对路径），直接返回会把内部字段泄露给前端。
 * VO 是"这一版接口承诺给外部的字段集合"，加字段要显式改这里。</p>
 */
@Data
public class FileRecordVO {

    /** t_file 主键 */
    private Long id;

    /** 业务分类 */
    private String bizCategory;

    /** 相对路径（落库用） */
    private String path;

    /** 可访问路径（展示用） */
    private String url;

    /** 浏览器上报的 Content-Type，仅参考 */
    private String contentType;

    /** 文件字节数 */
    private Long sizeBytes;

    /** 上传者类型：ADMIN / USER */
    private String uploaderType;

    /** 上传者 id */
    private Long uploaderId;

    /** 上传时间 */
    private LocalDateTime createdAt;

    /**
     * 实体 → VO。
     *
     * <p>字段逐个显式赋值而不是 BeanUtils.copyProperties：
     * 同名不同义的字段（filePath/path、fileUrl/url）需要改名，
     * 反射拷贝会静默漏掉它们，手写反而不会错。</p>
     */
    public static FileRecordVO from(FileRecord record) {
        FileRecordVO vo = new FileRecordVO();
        vo.setId(record.getId());
        vo.setBizCategory(record.getBizCategory());
        vo.setPath(record.getFilePath());
        vo.setUrl(record.getFileUrl());
        vo.setContentType(record.getContentType());
        vo.setSizeBytes(record.getSizeBytes());
        vo.setUploaderType(record.getUploaderType());
        vo.setUploaderId(record.getUploaderId());
        vo.setCreatedAt(record.getCreatedAt());
        return vo;
    }
}
