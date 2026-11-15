package com.yuedong.file.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.file.model.vo.FileRecordVO;
import com.yuedong.file.model.vo.UploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储业务接口。
 */
public interface FileStorageService {

    /**
     * 保存一张图片：四道校验 → 落盘 → 插 t_file。
     *
     * @param file         multipart 文件
     * @param category     业务子目录（白名单外的值统一落 common）
     * @param uploaderType 上传者类型：ADMIN / USER
     * @param uploaderId   上传者 id（管理员 id 或用户 id）
     * @return t_file 主键 + 相对路径 + 可访问 URL
     */
    UploadVO saveImage(MultipartFile file, String category, String uploaderType, Long uploaderId);

    /**
     * 文件元数据分页（管理端审计用），按 id 倒序即"最新上传在前"。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，1~100
     * @return 当前页 + 总数
     */
    PageDTO<FileRecordVO> page(long page, long size);
}
