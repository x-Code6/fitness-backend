package com.yuedong.point.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.point.model.vo.RankItemVO;
import com.yuedong.point.service.RankService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /** 月度挑战赛积分榜（Top50 + 我的排名） */
    @GetMapping("/monthly")
    public Result<List<RankItemVO>> monthly() {
        return Result.ok(rankService.monthly(UserContext.getUserId()));
    }
}
