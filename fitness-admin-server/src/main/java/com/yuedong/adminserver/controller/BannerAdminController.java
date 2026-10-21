package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.BannerAdminDTO;
import com.yuedong.course.api.dto.BannerSaveDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端活动页 BFF：课程服务负责数据，本层只做转发与操作日志。
 */
@RestController
@RequestMapping("/admin/banner")
@RequiredArgsConstructor
public class BannerAdminController {

    private final CourseAdminClient courseAdminClient;

    @GetMapping("/page")
    public Result<PageDTO<BannerAdminDTO>> page(@RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "10") Integer size) {
        return courseAdminClient.bannerPage(page, size);
    }

    @OperateLog(module = "活动页", operation = "编辑活动页")
    @PostMapping("/save")
    public Result<Void> save(@RequestBody BannerSaveDTO dto) {
        return courseAdminClient.saveBanner(dto);
    }

    @OperateLog(module = "活动页", operation = "活动页上下架")
    @PostMapping("/status")
    public Result<Void> status(@RequestParam Long id, @RequestParam Integer status) {
        return courseAdminClient.setBannerStatus(id, status);
    }
}
