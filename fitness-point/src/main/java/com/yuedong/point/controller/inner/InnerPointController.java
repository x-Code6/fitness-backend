package com.yuedong.point.controller.inner;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.point.api.dto.GiftDTO;
import com.yuedong.point.api.dto.GiftSaveDTO;
import com.yuedong.point.api.dto.PointBriefDTO;
import com.yuedong.point.service.CheckinService;
import com.yuedong.point.service.GiftService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 积分服务内部接口（fitness-admin-server Feign 调用）。
 * 签名与 fitness-point-api 的 PointClient 一一对应。
 */
@RestController
@RequestMapping("/inner/point")
@RequiredArgsConstructor
public class InnerPointController {

    private final GiftService giftService;
    private final CheckinService checkinService;

    /** 会员积分简况批量查询（管理端会员页聚合用；userIds 为逗号分隔的用户 id） */
    @GetMapping("/brief")
    public Result<List<PointBriefDTO>> brief(@RequestParam String userIds) {
        List<Long> ids = Arrays.stream(userIds.split(",")).map(Long::valueOf).toList();
        return Result.ok(checkinService.brief(ids));
    }

    /** 管理端：商品分页 */
    @GetMapping("/gift/page")
    public Result<PageDTO<GiftDTO>> giftPage(@RequestParam(defaultValue = "1") Integer page,
                                             @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(giftService.adminPage(page, size));
    }

    /** 管理端：新增/编辑商品 */
    @PostMapping("/gift/save")
    public Result<Void> giftSave(@Valid @RequestBody GiftSaveDTO dto) {
        giftService.adminSave(dto);
        return Result.ok();
    }

    /** 管理端：补库存 */
    @PostMapping("/gift/stock")
    public Result<Void> giftStock(@RequestParam Long giftId, @RequestParam Integer add) {
        giftService.addStock(giftId, add);
        return Result.ok();
    }
}
