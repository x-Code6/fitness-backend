package com.yuedong.file.controller;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.file.auth.AdminIdentity;
import com.yuedong.file.auth.AdminJwtVerifier;
import com.yuedong.file.constant.FileConstants;
import com.yuedong.file.model.vo.FileRecordVO;
import com.yuedong.file.model.vo.UploadVO;
import com.yuedong.file.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端文件接口：上传图片 + 文件元数据分页。
 *
 * <p>★认证为什么在本服务内做，而不是靠网关：网关那层验的是<b>用户端</b> JWT，
 * 管理端 token 是 admin-server 用另一套密钥签的，网关验不了。所以
 * {@code /api/file/admin/**} 在网关白名单放行，由本控制器调用 {@link AdminJwtVerifier}
 * 验签——放行不等于不设防，只是把验签点放在唯一持有管理端密钥的服务侧。</p>
 *
 * <p>★不限制角色：OPERATOR 也要能传课程封面/门店图，
 * 与旧 admin-server 的 FileUploadController 行为保持一致（那版也没有 @RequireRole）。</p>
 */
@RestController
@RequestMapping("/file/admin")
@RequiredArgsConstructor
public class AdminFileController {

    private final FileStorageService fileStorageService;
    private final AdminJwtVerifier adminJwtVerifier;

    /**
     * 上传一张图片。
     *
     * @param authorization 管理端 JWT（Bearer），必填
     * @param file          multipart 字段名固定为 {@code file}（前端 el-upload 的默认字段名）
     * @param category      业务子目录：course/store/coach/gift/video/community/banner/avatar/post/common，
     *                      白名单外的值统一落 common
     * @return t_file 主键 + 相对路径（落库）+ 访问路径（展示）
     */
    @PostMapping("/upload/image")
    public Result<UploadVO> uploadImage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false, defaultValue = "common") String category) {
        AdminIdentity admin = adminJwtVerifier.verify(authorization);
        UploadVO vo = fileStorageService.saveImage(
                file, category, FileConstants.UPLOADER_TYPE_ADMIN, admin.adminId());
        return Result.ok(vo);
    }

    /**
     * 文件元数据分页（管理端审计/素材管理用）。
     *
     * <p>按 id 倒序，最新上传在前。只返回元数据，不含文件内容。</p>
     *
     * @param authorization 管理端 JWT（Bearer），必填
     * @param page          页码，从 1 开始
     * @param size          每页条数，1~100
     */
    @GetMapping("/page")
    public Result<PageDTO<FileRecordVO>> page(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        // 分页也要登录：上传记录里有管理员 id 与业务分类，属于后台数据
        adminJwtVerifier.verify(authorization);
        PageDTO<FileRecordVO> result = fileStorageService.page(page, size);
        return Result.ok(result);
    }
}
