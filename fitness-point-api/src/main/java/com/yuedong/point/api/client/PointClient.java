package com.yuedong.point.api.client;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.point.api.dto.GiftDTO;
import com.yuedong.point.api.dto.GiftSaveDTO;
import com.yuedong.point.api.dto.PointBriefDTO;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 积分服务契约（管理端会员积分聚合 + 积分商城管理 Feign 调用）。
 */
@FeignClient(name = "fitness-point")
public interface PointClient {

    /** 会员积分简况批量查询（userIds 为逗号分隔的用户 id） */
    @GetMapping("/inner/point/brief")
    Result<List<PointBriefDTO>> brief(@RequestParam("userIds") String userIds);

    /** 管理端：商品分页 */
    @GetMapping("/inner/point/gift/page")
    Result<PageDTO<GiftDTO>> giftPage(@RequestParam("page") Integer page,
                                      @RequestParam("size") Integer size);

    /** 管理端：新增/编辑商品 */
    @PostMapping("/inner/point/gift/save")
    Result<Void> giftSave(@Valid @RequestBody GiftSaveDTO dto);

    /** 管理端：补库存 */
    @PostMapping("/inner/point/gift/stock")
    Result<Void> giftStock(@RequestParam("giftId") Long giftId, @RequestParam("add") Integer add);
}
