package com.yuedong.course.controller.inner;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.SeckillAdminDTO;
import com.yuedong.course.api.dto.SeckillSaveDTO;
import com.yuedong.course.service.SeckillActivityService;
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
 * 秒杀活动管理内部接口（fitness-admin-server Feign 调用）。
 * 签名与 fitness-course-api 的 CourseAdminClient（秒杀域）一一对应。
 */
@RestController
@RequestMapping("/inner/seckill")
@RequiredArgsConstructor
public class InnerSeckillController {

    private final SeckillActivityService seckillActivityService;

    /** 管理端：全部活动列表（含课程名） */
    @GetMapping("/admin/list")
    public Result<List<SeckillAdminDTO>> adminList() {
        return Result.ok(seckillActivityService.adminList());
    }

    /** 管理端：创建活动 */
    @PostMapping("/admin/save")
    public Result<Void> adminSave(@Valid @RequestBody SeckillSaveDTO dto) {
        seckillActivityService.adminSave(dto);
        return Result.ok();
    }

    /** 管理端：提前结束活动 */
    @PostMapping("/admin/end")
    public Result<Void> adminEnd(@RequestParam Long id) {
        seckillActivityService.adminEnd(id);
        return Result.ok();
    }
}
