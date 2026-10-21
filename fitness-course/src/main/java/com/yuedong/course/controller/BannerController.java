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
 *
 * <p>★路径必须带 {@code /course}：网关只做 {@code StripPrefix=1} 去掉 {@code /api}，
 * 不会去掉 {@code /course}。H5 请求 {@code /api/course/banner/list} 到达本服务时
 * 就是 {@code /course/banner/list}；写成 {@code /banner} 会让首页一直 404，
 * 前端只能回退到默认活动卡。</p>
 */
@RestController
@RequestMapping("/course/banner")
@RequiredArgsConstructor
public class BannerController {

    private final BannerService bannerService;

    @GetMapping("/list")
    public Result<List<BannerDTO>> list() {
        return Result.ok(bannerService.listActive());
    }
}
