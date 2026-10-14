package com.yuedong.adminserver.service;

import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.adminserver.model.vo.UploadVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * 图片落本地磁盘（docs D19）。<b>这个类的全部价值在于它拒绝了什么</b>：
 * 上传是最容易写出远程代码执行的文件入口，所以四道判断一道都不能省。
 *
 * <ol>
 *   <li><b>后缀白名单</b>（jpg/jpeg/png/webp/gif）——
 *       黑名单（"不许传 .jsp/.exe"）一定会漏，白名单不会；</li>
 *   <li><b>魔数校验</b>——后缀是可以随便改的，把 {@code shell.jsp} 改名成 {@code photo.jpg}
 *       就过了第 1 道。读文件头几个字节才知道它<b>真的</b>是什么；</li>
 *   <li><b>UUID 重命名</b>——原始文件名<b>一次都不进文件系统</b>。
 *       原始名里可能有 {@code ../}、可能有中文、可能有零字节、可能撞上同名文件被覆盖；</li>
 *   <li><b>目录归一后判 {@code startsWith} </b>——防目录穿越的最后一道。
 *       业务分类用的是白名单，但"白名单已经挡住了"不等于可以不写这道判断：
 *       将来有人加一个 {@code category} 来自配置或用户输入，这道判断就是唯一防线。</li>
 * </ol>
 *
 * <p>★<b>不做的事</b>（本期决定，见 D19）：不接 OSS、不做缩略图、不做视频上传
 * （视频存外部 http 链接）。本地磁盘只有一个实例，够用且可讲清原理。</p>
 */
@Slf4j
@Service
public class FileStorageService {

    /** 允许的<b>真实</b>图片格式（白名单） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /**
     * 允许的业务子目录（白名单）。
     * <p>★分类不来自前端自由输入：<b>能拼进路径的每一段都必须是白名单里的值</b>，
     * 否则 {@code category=../../etc} 这种就会走到磁盘上去。</p>
     */
    private static final Set<String> ALLOWED_CATEGORIES =
            Set.of("course", "store", "coach", "gift", "video", "community");

    private static final String DEFAULT_CATEGORY = "common";

    /** 单张上限 2MB。★图片上传的绝大多数滥用都是"传一张 40MB 的图撑满磁盘" */
    private static final long MAX_SIZE_BYTES = 2L * 1024 * 1024;

    /** 磁盘根目录（相对启动目录或绝对路径，配置项 file.upload-root） */
    @Value("${file.upload-root:./uploads}")
    private String uploadRoot;

    /**
     * 保存图片。
     *
     * @param file     multipart 文件
     * @param category 业务子目录（白名单外的一律落到 {@code common}）
     * @return 相对路径 + 可访问 URL
     */
    public UploadVO saveImage(MultipartFile file, String category) {
        // ===== 第 1 道：非空与大小 =====
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片不能超过 2MB");
        }

        // ===== 第 2 道：后缀白名单（从原始文件名取，只用来判类型，绝不用于落盘名）=====
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "只允许上传 jpg/jpeg/png/webp/gif 图片");
        }

        // ===== 第 3 道：魔数校验（后缀可伪造，文件头不能）=====
        if (!looksLikeImage(file, extension)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件内容与声明的图片格式不一致");
        }

        // ===== 第 4 道：分类白名单 + 路径归一后必须仍在根目录内 =====
        String safeCategory = ALLOWED_CATEGORIES.contains(category) ? category : DEFAULT_CATEGORY;
        Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
        Path dir = root.resolve(safeCategory).normalize();
        // ★这一句是整个类的核心防线。normalize() 把 .. 折叠掉之后，
        //   越界的结果会是"不在 root 下面"，startsWith 一比就露出来
        if (!dir.startsWith(root)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传目录不合法");
        }

        // ===== 落盘：UUID 名，原始文件名到此为止完全没有参与路径 =====
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件名不合法");
        }
        try {
            Files.createDirectories(dir);
            file.transferTo(target.toFile());
        } catch (IOException e) {
            log.error("图片写入失败: target={}", target, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片保存失败，请重试");
        }

        String relativePath = safeCategory + "/" + fileName;
        // ★返回给前端的是访问路径而不是磁盘路径：磁盘路径不该让浏览器知道
        return new UploadVO(relativePath, "/uploads/" + relativePath);
    }

    /** 取后缀：只认最后一个点之后的部分，并统一小写。原始名可能带路径分隔符，先截掉 */
    private String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        String name = originalName.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase();
    }

    /**
     * 读文件头判断真实类型。
     * <p>★为什么不只信 {@code file.getContentType()}：那是<b>浏览器自己写的</b>，
     * 攻击者可以传 {@code Content-Type: image/png} 而 body 是一段脚本。</p>
     * <p>一期只认四种最常见的头：JPEG / PNG / GIF / WEBP（WEBP 头里有两处要一起看）。</p>
     */
    private boolean looksLikeImage(MultipartFile file, String extension) {
        byte[] header = new byte[12];
        try (InputStream in = file.getInputStream()) {
            int read = in.read(header);
            if (read < 12) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        boolean jpeg = (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
        boolean png = (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
        boolean gif = header[0] == 'G' && header[1] == 'I' && header[2] == 'F';
        boolean webp = header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        boolean matched = switch (extension) {
            case "jpg", "jpeg" -> jpeg;
            case "png" -> png;
            case "gif" -> gif;
            case "webp" -> webp;
            default -> false;
        };
        return matched;
    }
}
