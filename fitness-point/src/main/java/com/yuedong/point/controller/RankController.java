package com.yuedong.point.controller;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.point.model.vo.RankItemVO;
import com.yuedong.point.service.RankService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排行榜接口（需登录）。
 */
@RestController
@RequestMapping("/rank")
@RequiredArgsConstructor
public class RankController {

    private final RankService rankService;

    /** 本月实时榜（Top50 + 我的排名） */
    @GetMapping("/monthly")
    public Result<List<RankItemVO>> monthly() {
        return Result.ok(rankService.monthly(UserContext.getUserId()));
    }

    /**
     * 历史月榜单（归档表，分页）。
     *
     * @param season 月份 yyyyMM，如 202609
     */
    @GetMapping("/history")
    public Result<PageDTO<RankItemVO>> history(@RequestParam String season,
                                               @RequestParam(defaultValue = "1") Integer page,
                                               @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(rankService.history(season, page, size, UserContext.getUserId()));
    }
}
