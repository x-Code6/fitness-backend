package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseAdminClient;
import com.yuedong.course.api.dto.SeckillAdminDTO;
import com.yuedong.course.api.dto.SeckillSaveDTO;
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
 * 秒杀活动管理。
 */
@RestController
@RequestMapping("/admin/seckill")
@RequiredArgsConstructor
public class SeckillAdminController {

    private final CourseAdminClient courseClient;

    /** 全部活动列表（含课程名/库存/状态） */
    @GetMapping("/list")
    public Result<List<SeckillAdminDTO>> list() {
        return courseClient.seckillList();
    }

    @OperateLog(module = "秒杀活动", operation = "创建活动")
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody SeckillSaveDTO dto) {
        return courseClient.seckillSave(dto);
    }

    @OperateLog(module = "秒杀活动", operation = "提前结束活动")
    @PostMapping("/end")
    public Result<Void> end(@RequestParam Long id) {
        return courseClient.seckillEnd(id);
    }
}
