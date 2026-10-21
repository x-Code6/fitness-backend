package com.yuedong.course.controller;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.BannerDTO;
import com.yuedong.course.service.BannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端首页活动轮播（公开接口，网关白名单已放行）。
 */
@RestController
@RequestMapping("/banner")
@RequiredArgsConstructor
public class BannerController {

    private final BannerService bannerService;

    @GetMapping("/list")
    public Result<List<BannerDTO>> list() {
        return Result.ok(bannerService.listActive());
    }
}
