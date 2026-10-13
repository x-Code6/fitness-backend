package com.yuedong.course.controller;

import com.yuedong.common.result.Result;
import com.yuedong.course.model.vo.StoreVO;
import com.yuedong.course.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 门店接口（GEO 距离排序）。
 */
@RestController
@RequestMapping("/store")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    /**
     * 附近门店：按距离升序，支持关键词。
     *
     * @param keyword 匹配门店名/地址/特色标签
     */
    @GetMapping("/nearby")
    public Result<List<StoreVO>> nearby(@RequestParam(required = false) String keyword) {
        return Result.ok(storeService.nearby(keyword));
    }
}
