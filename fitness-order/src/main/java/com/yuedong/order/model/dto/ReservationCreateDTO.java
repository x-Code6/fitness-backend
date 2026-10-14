package com.yuedong.order.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建预约的入参（对应前端 {@code POST /reservation/create}）。
 *
 * <p>★为什么同时传 {@code courseId}（凭证里已经有）：不是要让后端相信前端，
 * 而是<b>多一道一致性校验</b>。请求说"约这门课的教练"，凭证说"我买的是那门课"，
 * 两者不符直接拒。少了这道判断，前端把课程页与预约页的 id 串错时会出现
 * "用 A 课的凭证约了 B 课的教练"——每一步看起来都合法，数据却脏了，事后极难归因。</p>
 */
@Data
public class ReservationCreateDTO {

    /** 用哪张凭证换这次预约 */
    @NotNull(message = "请选择要使用的凭证")
    private Long entitlementId;

    /** 约哪位教练 */
    @NotNull(message = "请选择教练")
    private Long coachId;

    /** 约哪门课。★与凭证的 course_id 必须一致，见类注释 */
    @NotNull(message = "缺少课程信息")
    private Long courseId;

    /** 哪一天，格式 yyyy-MM-dd。★由前端选日期传进来，后端不猜"今天/明天" */
    @NotBlank(message = "请选择上课日期")
    private String date;

    /**
     * 整点起始时刻，格式 HH:mm（如 "18:00"）。
     * <p>★只接受整点：占用表一行占一个整点格子，非整点会让
     * {@code uk_coach_start} 的"格子存在性判断"失去意义（脏数据拦不住），
     * 所以这里的格式校验是业务前提，不是吹毛求疵。</p>
     */
    @NotBlank(message = "请选择上课时间")
    private String startTime;
}
