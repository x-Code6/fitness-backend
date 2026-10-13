package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.point.api.client.PointClient;
import com.yuedong.point.api.dto.GiftDTO;
import com.yuedong.point.api.dto.GiftSaveDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分商城商品管理。
 */
@RestController
@RequestMapping("/admin/gift")
@RequiredArgsConstructor
public class GiftAdminController {

    private final PointClient pointClient;

    /** 商品分页 */
    @GetMapping("/page")
    public Result<PageDTO<GiftDTO>> page(@RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "10") Integer size) {
        return pointClient.giftPage(page, size);
    }

    @OperateLog(module = "积分商城", operation = "新增/编辑商品")
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody GiftSaveDTO dto) {
        return pointClient.giftSave(dto);
    }

    @OperateLog(module = "积分商城", operation = "补库存")
    @PostMapping("/stock")
    public Result<Void> stock(@RequestParam Long giftId, @RequestParam(defaultValue = "10") Integer add) {
        return pointClient.giftStock(giftId, add);
    }
}
