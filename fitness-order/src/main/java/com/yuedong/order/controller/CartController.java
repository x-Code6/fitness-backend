package com.yuedong.order.controller;

import com.yuedong.common.idempotent.Idempotent;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.order.model.dto.CartAddDTO;
import com.yuedong.order.model.dto.CartItemUpdateDTO;
import com.yuedong.order.model.dto.CheckoutDTO;
import com.yuedong.order.model.vo.CartItemVO;
import com.yuedong.order.model.vo.CartListVO;
import com.yuedong.order.model.vo.CheckoutResultVO;
import com.yuedong.order.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车接口（需登录）。
 *
 * <p>网关路由：{@code /api/cart/**} → 本服务 {@code /cart/**}。</p>
 *
 * <p>★前端"购物车角标"用的是 {@code GET /cart/list} 再自己累加份数
 * （{@code fitness-web/src/api/cart.js#getCartCount}），所以这里没有单独做 count 接口：
 * 一个只返回数字的接口和整页数据迟早对不上，不如只有一个来源。</p>
 */
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /**
     * 加购。
     * <p>★加幂等（"值得防"档）：连点两下"加入购物车"，无幂等时第一下插入、第二下累加，
     * 用户会得到"1 件变 2 件"却没做任何操作——份数是会直接变成钱的，这一档必须挡。
     * 而唯一索引在这里帮不上忙：累加本来就是合法行为。</p>
     */
    @Idempotent(prefix = "cartAdd", expireSecond = 2)
    @PostMapping("/add")
    public Result<CartItemVO> add(@Valid @RequestBody CartAddDTO dto) {
        return Result.ok(cartService.add(UserContext.getUserId(), dto));
    }

    /** 改份数或勾选状态（传了才改） */
    @PostMapping("/update")
    public Result<Void> update(@Valid @RequestBody CartItemUpdateDTO dto) {
        cartService.update(UserContext.getUserId(), dto);
        return Result.ok();
    }

    /** 删除条目。★复用同一个 DTO：只需要 itemId 这一个字段 */
    @PostMapping("/remove")
    public Result<Void> remove(@Valid @RequestBody CartItemUpdateDTO dto) {
        cartService.remove(UserContext.getUserId(), dto.getItemId());
        return Result.ok();
    }

    /** 购物车整页（条目 + 合计 + 已选数） */
    @GetMapping("/list")
    public Result<CartListVO> list() {
        return Result.ok(cartService.list(UserContext.getUserId()));
    }

    /**
     * 结算：拆成 N 张订单 + 同一组号。
     * <p>★不加幂等：结算失败后用户"再点一次"是<b>真的想再试一次</b>，
     * 用时间窗口挡住反而把他锁在错误页面上。这里的正确防线是
     * 建单前的可售校验 + 失败时待支付单会 15 分钟自动取消。</p>
     */
    @PostMapping("/checkout")
    public Result<CheckoutResultVO> checkout(@Valid @RequestBody CheckoutDTO dto) {
        return Result.ok(cartService.checkout(UserContext.getUserId(), dto));
    }
}
