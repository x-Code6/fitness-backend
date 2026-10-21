package com.yuedong.course.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.BannerAdminDTO;
import com.yuedong.course.api.dto.BannerSaveDTO;
import com.yuedong.course.service.BannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端活动页内部接口（fitness-admin-server BFF 调用）。
 */
@RestController
@RequestMapping("/inner/course/banner")
@RequiredArgsConstructor
public class InnerBannerController {

    private final BannerService bannerService;

    @GetMapping("/page")
    public Result<PageDTO<BannerAdminDTO>> page(@RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(bannerService.adminPage(page, size));
    }

    @PostMapping("/save")
    public Result<Void> save(@RequestBody BannerSaveDTO dto) {
        bannerService.adminSave(dto);
        return Result.ok();
    }

    @PostMapping("/status")
    public Result<Void> status(@RequestParam Long id, @RequestParam Integer status) {
        bannerService.setStatus(id, status);
        return Result.ok();
    }
}
