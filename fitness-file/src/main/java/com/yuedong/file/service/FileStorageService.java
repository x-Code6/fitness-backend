package com.yuedong.file.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.file.mapper.FileRecordMapper;
import com.yuedong.file.model.entity.FileRecord;
import com.yuedong.file.model.vo.FileRecordVO;
import com.yuedong.file.model.vo.UploadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 图片落本地磁盘 + 写 t_file 元数据。
 *
 * <p><b>这个类的全部价值首先在于它拒绝了什么</b>：上传是最容易写出远程代码执行的入口，
 * 所以四道判断一道都不能省：</p>
 * <ol>
 *   <li><b>后缀白名单</b>（jpg/jpeg/png/webp/gif）——黑名单一定会漏，白名单不会；</li>
 *   <li><b>魔数校验</b>——后缀可以随便改，把脚本改名成 photo.jpg 就过了第 1 道，
 *       只有读文件头才知道它真的是什么；</li>
 *   <li><b>UUID 重命名</b>——原始文件名一次都不进文件系统，避免 ../、中文、零字节、重名覆盖；</li>
 *   <li><b>目录归一后判 startsWith</b>——防目录穿越的最后一道；
 *       白名单现在挡得住，不代表将来有人把 category 改成配置或用户输入时还挡得住。</li>
 * </ol>
 *
 * <p>★<b>磁盘与数据库的一致性策略</b>：先落盘、再插 t_file；插库失败就把刚写的文件删掉。
 * 顺序不能反——先插库再落盘的话，落盘失败会留下一条指向不存在文件的"幽灵记录"，
 * 比多一个无主文件更难发现；而插库失败时删文件是可控的补偿动作。</p>
 *
 * <p>★不加 @Transactional：一条 INSERT 本身就是原子的，而磁盘写入不参与数据库事务。
 * 加了事务只会制造"数据库回滚了、文件还在"的错觉，真正的一致性靠上面的补偿删除。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /** 上传者类型：管理端。与 t_file.uploader_type 的取值一致 */
    public static final String UPLOADER_TYPE_ADMIN = "ADMIN";

    /** 上传者类型：用户端 */
    public static final String UPLOADER_TYPE_USER = "USER";

    /** 允许的<b>真实</b>图片后缀（白名单） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /**
     * 允许的业务子目录（白名单）。
     * <p>★分类会拼进磁盘路径，所以每一段都必须是白名单里的值，
     * 否则 {@code category=../../etc} 这种就会走到磁盘上去。</p>
     */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "course", "store", "coach", "gift", "video", "community",
            "banner", "avatar", "post", "common");

    /** 未知分类统一落到 common，保证旧前端传空值/旧分类时上传不中断 */
    private static final String DEFAULT_CATEGORY = "common";

    /** 单张上限 2MB。★图片上传的绝大多数滥用都是"传一张几十 MB 的图撑满磁盘" */
    private static final long MAX_SIZE_BYTES = 2L * 1024 * 1024;

    /** 管理端分页单页上限，防止一次把 t_file 全表拉出来 */
    private static final long MAX_PAGE_SIZE = 100L;

    private final FileRecordMapper fileRecordMapper;

    /** 磁盘根目录（相对启动目录或绝对路径，配置项 file.upload-root） */
    @Value("${file.upload-root:./uploads}")
    private String uploadRoot;

    /**
     * 保存一张图片：四道校验 → 落盘 → 插 t_file。
     *
     * @param file         multipart 文件
     * @param category     业务子目录（白名单外的值统一落 common）
     * @param uploaderType 上传者类型：ADMIN / USER
     * @param uploaderId   上传者 id（管理员 id 或用户 id）
     * @return t_file 主键 + 相对路径 + 可访问 URL
     */
    public UploadVO saveImage(MultipartFile file, String category, String uploaderType, Long uploaderId) {
        // ===== 第 1 道：非空与大小 =====
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "图片不能超过 2MB");
        }
        if (!UPLOADER_TYPE_ADMIN.equals(uploaderType) && !UPLOADER_TYPE_USER.equals(uploaderType)) {
            // 调用方必须显式说明是谁在传，否则审计列会写进无意义的值
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传者类型不合法");
        }

        // ===== 第 2 道：后缀白名单（原始名只用来判类型，绝不用于落盘名）=====
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
        String safeCategory = resolveCategory(category);
        Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
        Path dir = root.resolve(safeCategory).normalize();
        // ★normalize() 把 .. 折叠掉之后，越界的结果会是"不在 root 下面"，startsWith 一比就露出来
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
            file.transferTo(target);
        } catch (IOException | IllegalStateException e) {
            // 写了一半也算失败：把可能存在的半个文件删掉，别留残片
            deleteQuietly(target);
            log.error("图片写入失败: target={}", target, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片保存失败，请重试");
        }

        String relativePath = safeCategory + "/" + fileName;
        String fileUrl = "/uploads/" + relativePath;

        // ===== 落盘成功后再插库；插库失败必须删文件，避免"磁盘有、库里没有"的无主文件 =====
        FileRecord record = new FileRecord();
        record.setBizCategory(safeCategory);
        record.setFilePath(relativePath);
        record.setFileUrl(fileUrl);
        record.setContentType(file.getContentType());
        record.setSizeBytes(file.getSize());
        record.setUploaderType(uploaderType);
        record.setUploaderId(uploaderId);
        // 显式写时间：让插入后返回的 VO/后续分页能看到准确时间，不依赖数据库默认值回读
        record.setCreatedAt(LocalDateTime.now());
        try {
            fileRecordMapper.insert(record);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            log.error("文件元数据写入失败，已回删磁盘文件: path={}", relativePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片保存失败，请重试");
        }

        // ★返回给前端的是访问路径而不是磁盘路径：磁盘路径不该让浏览器知道
        return new UploadVO(record.getId(), relativePath, fileUrl);
    }

    /**
     * 文件元数据分页（管理端审计用），按 id 倒序即"最新上传在前"。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，1~100
     * @return 当前页 + 总数
     */
    public PageDTO<FileRecordVO> page(long page, long size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分页参数不合法：page≥1，1≤size≤100");
        }
        Page<FileRecord> pageParam = new Page<>(page, size);
        Page<FileRecord> result = fileRecordMapper.selectPage(
                pageParam,
                Wrappers.<FileRecord>lambdaQuery().orderByDesc(FileRecord::getId));
        List<FileRecordVO> list = result.getRecords().stream()
                .map(FileRecordVO::from)
                .toList();
        return PageDTO.of(list, result.getTotal());
    }

    /** 分类兜底：空值或白名单外的值落 common（用户端的严格限制在 UserFileController 里做） */
    private String resolveCategory(String category) {
        if (category == null || category.isBlank()) {
            return DEFAULT_CATEGORY;
        }
        String trimmed = category.trim();
        return ALLOWED_CATEGORIES.contains(trimmed) ? trimmed : DEFAULT_CATEGORY;
    }

    /** 取后缀：只认最后一个点之后的部分并小写；原始名可能带路径分隔符，先截掉目录 */
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
     *
     * <p>★为什么不信 {@code file.getContentType()}：那是浏览器自己写的，
     * 攻击者完全可以传 {@code Content-Type: image/png} 而 body 是一段脚本。</p>
     * <p>一期只认 JPEG / PNG / GIF / WEBP 四种头（WEBP 头里有两处要一起看）。</p>
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
        return switch (extension) {
            case "jpg", "jpeg" -> jpeg;
            case "png" -> png;
            case "gif" -> gif;
            case "webp" -> webp;
            default -> false;
        };
    }

    /**
     * 尽力删除文件。补偿删除本身失败时只记日志、不覆盖原始异常：
     * 调用方需要看到"插库失败"这个真正原因，而不是"删文件也失败了"。
     */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("补偿删除文件失败，需要人工清理: path={}", path, e);
        }
    }
}
