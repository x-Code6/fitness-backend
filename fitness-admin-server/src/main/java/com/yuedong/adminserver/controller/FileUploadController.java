package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.adminserver.model.vo.UploadVO;
import com.yuedong.adminserver.service.FileStorageService;
import com.yuedong.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端图片上传（D19：本地磁盘，不接 OSS）。
 *
 * <p>★为什么放在 admin-server 而不是各业务服务：
 * 上传的调用方只有管理端（课程封面、门店图、教练照片、礼品图），
 * 而文件一旦落到磁盘，"谁能写"就必须和"谁能改数据"是同一套权限。
 * 各服务自己开一个上传口，等于把同一个目录开四个写入入口。</p>
 *
 * <p>★这个类<b>没有</b> {@code @RequireRole}：OPERATOR 也要能传课程封面。
 * 但它仍然在管理端 JWT 的保护之下——认证拦截器覆盖 {@code /admin/**}，
 * 只有 captcha/login 和静态资源 {@code /uploads/**} 是例外。</p>
 */
@RestController
@RequestMapping("/admin/upload")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileStorageService fileStorageService;

    /**
     * 上传一张图片。
     *
     * @param file     multipart 字段名固定为 {@code file}（前端 el-upload 的默认字段名）
     * @param category 业务子目录：course / store / coach / gift / video / community，
     *                 白名单外的值统一落 {@code common}
     * @return 相对路径（落库）+ 访问路径（展示）
     */
    @OperateLog(module = "文件上传", operation = "上传图片")
    @PostMapping("/image")
    public Result<UploadVO> image(@RequestParam("file") MultipartFile file,
                                  @RequestParam(required = false, defaultValue = "common") String category) {
        return Result.ok(fileStorageService.saveImage(file, category));
    }
}
