package com.yuedong.course.controller.inner;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.course.api.dto.StoreSaveDTO;
import com.yuedong.course.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店管理内部接口（fitness-admin-server Feign 调用）。
 * 签名与 fitness-course-api 的 CourseAdminClient（门店域）一一对应。
 */
@RestController
@RequestMapping("/inner/store")
@RequiredArgsConstructor
public class InnerStoreController {

    private final StoreService storeService;

    /** 管理端：门店列表（全量） */
    @GetMapping("/list")
    public Result<List<StoreDTO>> list() {
        return Result.ok(storeService.adminList());
    }

    /**
     * 内部：单店详情（order 服务算可约时段要营业时间、订单详情要地址）。
     *
     * <p>★门店不存在时返回 {@code data=null} 而不是报错：管理员删店是正常操作，
     * 而订单/预约里的门店名是快照，本来就该继续显示当时的名字。
     * 调用方判空后按"不显示这一行"处理。</p>
     */
    @GetMapping("/detail")
    public Result<StoreDTO> detail(@RequestParam Long storeId) {
        return Result.ok(storeService.detail(storeId));
    }

    /** 管理端：新增/编辑门店 */
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody StoreSaveDTO dto) {
        storeService.adminSave(dto);
        return Result.ok();
    }

    /** 管理端：删除门店 */
    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam Long id) {
        storeService.adminDelete(id);
        return Result.ok();
    }
}
