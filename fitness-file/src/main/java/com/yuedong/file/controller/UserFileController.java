package com.yuedong.file.controller;

import com.yuedong.common.constant.HttpConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.file.constant.FileConstants;
import com.yuedong.file.model.vo.UploadVO;
import com.yuedong.file.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 用户端文件接口：头像 / 动态配图 / 社区图上传。
 *
 * <p>★用户身份从 {@code X-User-Id} 头取，不解析用户 JWT：
 * 网关在验签并校验 jti 白名单后会覆盖写入这个头，服务侧只信任网关的值。
 * 因此本接口<b>不能</b>在网关上白名单——{@code /api/file/user/**} 没有白名单条目，
 * 匿名请求在网关就被 401 拦掉，不会到达这里。</p>
 *
 * <p>★服务端口 8108 只应在内网/Nacos 可达：如果直接暴露公网，
 * 外部可以自己伪造 X-User-Id 头。部署上必须和网关同处一个内网。</p>
 */
@RestController
@RequestMapping("/file/user")
@RequiredArgsConstructor
public class UserFileController {

    /**
     * 用户端允许的分类白名单。
     * <p>★用户不能往 course/store/coach 这些管理端目录写：那会让普通用户
     * 覆盖运营素材的命名空间，也绕过了管理端的内容审核链路。</p>
     */
    private static final Set<String> USER_ALLOWED_CATEGORIES = Set.of("avatar", "post", "community");

    private final FileStorageService fileStorageService;

    /**
     * 上传一张图片（用户端）。
     *
     * @param userIdHeader 网关验签后注入的用户 id，必填
     * @param file         multipart 文件
     * @param category     avatar / post / community，其他值直接拒绝（不兜底到 common）
     * @return t_file 主键 + 相对路径 + 访问路径
     */
    @PostMapping("/upload/image")
    public Result<UploadVO> uploadImage(
            @RequestHeader(value = HttpConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @RequestParam("file") MultipartFile file,
            @RequestParam("category") String category) {
        Long userId = requireUserId(userIdHeader);
        String trimmedCategory = category == null ? "" : category.trim();
        if (!USER_ALLOWED_CATEGORIES.contains(trimmedCategory)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "用户端只允许上传 avatar/post/community 分类的图片");
        }
        UploadVO vo = fileStorageService.saveImage(
                file, trimmedCategory, FileConstants.UPLOADER_TYPE_USER, userId);
        return Result.ok(vo);
    }

    /**
     * 读取并校验网关注入的用户 id。
     *
     * <p>为空说明请求没经过网关（或网关判定未登录），按未登录处理；
     * 不是数字说明头被污染，同样不能继续——宁可 401，也不能拿 null 去写审计列。</p>
     */
    private Long requireUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        try {
            return Long.valueOf(userIdHeader.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态无效，请重新登录");
        }
    }
}
