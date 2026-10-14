package com.yuedong.course.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端新增/编辑门店请求参数。
 *
 * <p>★{@code rating} 与 {@code coaches} 两个旧字段被删除：
 * 评分由评价聚合而来（数据在 yd_order），教练数由教练表实时数出。
 * 两者都不再是"可由管理员手填"的属性——留着这两个输入框，
 * 就等于允许人工值覆盖真实值，形成谁也说不清的第二份真相。</p>
 */
@Data
public class StoreSaveDTO {

    /** 编辑时传 id；null 表示新增 */
    private Long id;

    @NotBlank(message = "门店名不能为空")
    @Size(max = 50, message = "门店名不超过 50 字")
    private String name;

    @NotNull(message = "经度不能为空")
    private BigDecimal lng;

    @NotNull(message = "纬度不能为空")
    private BigDecimal lat;

    @NotBlank(message = "地址不能为空")
    @Size(max = 100, message = "地址不超过 100 字")
    private String address;

    @Size(max = 200, message = "简介不超过 200 字")
    private String intro;

    /** 标签，逗号分隔字符串 */
    @Size(max = 100, message = "标签不超过 100 字")
    private String tags;

    /** 图片相对路径：由上传接口返回后回填，不允许手输任意字符串 */
    @Size(max = 200)
    private String image;

    @NotNull(message = "请设置营业开始时间")
    @Min(value = 0, message = "营业开始整点为 0~23")
    @Max(value = 23, message = "营业开始整点为 0~23")
    private Integer openStartHour;

    @NotNull(message = "请设置营业结束时间")
    @Min(value = 1, message = "营业结束整点为 1~24")
    @Max(value = 24, message = "营业结束整点为 1~24")
    private Integer openEndHour;

    /** 1 全天营业：此时上面两个整点不参与计算 */
    @Min(value = 0, message = "是否 24 小时取 0 或 1")
    @Max(value = 1, message = "是否 24 小时取 0 或 1")
    private Integer is24h;

    /** 营业时间展示文案，如"07:00-23:00" */
    @Size(max = 20)
    private String openHoursText;

    @Size(max = 20)
    private String phone;

    @Min(value = 0, message = "状态取 0 或 1")
    @Max(value = 1, message = "状态取 0 或 1")
    private Integer status;
}
