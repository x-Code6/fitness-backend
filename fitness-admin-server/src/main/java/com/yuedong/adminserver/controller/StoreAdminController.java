package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.adminserver.annotation.RequireRole;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.course.api.dto.StoreSaveDTO;
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
 * 门店管理（仅超管：影响 GEO 与用户端展示面）。
 */
@RestController
@RequestMapping("/admin/store")
@RequiredArgsConstructor
@RequireRole("SUPER")
public class StoreAdminController {

    private final CourseAdminClient courseClient;

    @GetMapping("/list")
    public Result<List<StoreDTO>> list() {
        return courseClient.storeList();
    }

    @OperateLog(module = "门店管理", operation = "保存门店")
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody StoreSaveDTO dto) {
        return courseClient.storeSave(dto);
    }

    @OperateLog(module = "门店管理", operation = "删除门店")
    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam Long id) {
        return courseClient.storeDelete(id);
    }
}
