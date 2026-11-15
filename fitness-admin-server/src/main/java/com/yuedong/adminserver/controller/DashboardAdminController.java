package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.model.vo.DashboardVO;
import com.yuedong.adminserver.service.DashboardService;
import com.yuedong.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据看板入口：三个服务的数字拼一页。
 */
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class DashboardAdminController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public Result<DashboardVO> summary() {
        return Result.ok(dashboardService.summary());
    }
}
