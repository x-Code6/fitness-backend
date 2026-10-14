package com.yuedong.order.controller;

import com.yuedong.common.idempotent.Idempotent;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.dto.RatingDeleteDTO;
import com.yuedong.order.model.dto.RatingSaveDTO;
import com.yuedong.order.model.vo.RatingVO;
import com.yuedong.order.service.RatingService;
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
 * 评价接口（需登录）。
 *
 * <p>网关路由：{@code /api/rating/**} → 本服务 {@code /rating/**}。</p>
 *
 * <p>★<b>这里没有 update 接口</b>：一单一评、可删不可改（D10）。
 * 但"不暴露 update"本身不是保证——真正的保证是 {@code uk_order} 与软删保留行：
 * 删掉之后唯一键还在，第二次插不进去。
 * <b>业务规则落在数据库上才算落了，落在接口清单上只是愿望。</b></p>
 */
@RestController
@RequestMapping("/rating")
@RequiredArgsConstructor
public class RatingController {

    private final RatingService ratingService;

    /**
     * 提交评价。
     * <p>★加幂等：双击时唯一索引也会拦下第二个，但那样用户看到的是"本单已评价过"，
     * 而幂等窗口给的是"操作太快了"——同一个结果，后一种文案更容易被理解。
     * 两层都在，防线仍然是唯一索引。</p>
     */
    @Idempotent(prefix = "ratingSave", expireSecond = 5)
    @PostMapping("/save")
    public Result<Void> save(@Valid @RequestBody RatingSaveDTO dto) {
        ratingService.save(UserContext.getUserId(), dto);
        return Result.ok();
    }

    /** 删除评价（软删。删完这一单就再也评不了了，这是"不可改"的代价） */
    @PostMapping("/delete")
    public Result<Void> delete(@Valid @RequestBody RatingDeleteDTO dto) {
        ratingService.delete(UserContext.getUserId(), dto.getRatingId());
        return Result.ok();
    }

    /** 某门课的评价分页 */
    @GetMapping("/course")
    public Result<PageDTO<RatingVO>> course(@RequestParam Long courseId,
                                            @RequestParam(defaultValue = "1") Integer page,
                                            @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(ratingService.courseRatings(UserContext.getUserId(), courseId, page, size));
    }

    /** 我发过的评价 */
    @GetMapping("/mine")
    public Result<List<RatingVO>> mine() {
        return Result.ok(ratingService.mine(UserContext.getUserId()));
    }
}
