package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.RequireRole;
import com.yuedong.adminserver.model.vo.OperateLogVO;
import com.yuedong.adminserver.service.LogService;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询（仅超管）。
 */
@RestController
@RequestMapping("/admin/log")
@RequiredArgsConstructor
@RequireRole("SUPER")
public class LogController {

    private final LogService logService;

    /** 操作日志分页（倒序） */
    @GetMapping("/page")
    public Result<PageDTO<OperateLogVO>> page(@RequestParam(defaultValue = "1") Integer page,
                                              @RequestParam(defaultValue = "15") Integer size) {
        return Result.ok(logService.page(page, size));
    }
}
