package com.yuedong.order.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 取消预约的入参。
 *
 * <p>★字段名必须是 {@code reservationNo} 而不是复用订单那边的 {@code CancelDTO{id}}：
 * 前端 {@code fitness-web/src/api/reservation.js} 发的是
 * {@code {reservationNo: ...}}。两个接口长得像不代表能用同一个 DTO——
 * 一个是取消<b>订单</b>（钱），一个是取消<b>预约</b>（时段），
 * 混用一个 DTO 迟早让人以为它们是一回事。</p>
 */
@Data
public class ReservationCancelDTO {

    /** 要取消的预约单号（R 开头，不是订单号） */
    @NotBlank(message = "缺少预约单号")
    private String reservationNo;
}
