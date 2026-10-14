package com.yuedong.order.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 改购物车条目：份数与勾选状态（对应前端 {@code /cart/update} 与 {@code /cart/remove}）。
 *
 * <p>★为什么一个 DTO 服务两个接口：{@code update} 传的是
 * {@code {itemId, quantity?, selected?}}——<b>两个字段都可能不传</b>，
 * "传了才改"是这张表单的语义（前端加减号只发 quantity，勾选框只发 selected）。
 * 拆成两个 DTO 反而要各写一遍"非空才更新"。
 * {@code remove} 只用 {@code itemId}，复用最省事。</p>
 */
@Data
public class CartItemUpdateDTO {

    @NotNull(message = "缺少条目 id")
    private Long itemId;

    /** 传了就改份数 */
    @Min(value = 1, message = "份数至少为 1")
    @Max(value = 10, message = "单个条目最多 10 份")
    private Integer quantity;

    /** 传了就改勾选：1 勾选 / 0 不勾选。用 Integer 而不是 boolean，和前端发的 0/1 一致 */
    private Integer selected;
}
