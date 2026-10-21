package com.yuedong.course.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端创建/编辑课程请求参数。
 *
 * <p>★v2 删掉的入参：{@code coach}/{@code coachTitle}（教练改由"教练配课"维护）、
 * {@code storeName}（可售门店改由 {@code /inner/course/admin/assign-store} 维护，
 * 而且是 id 不是名字——按名字关联数据是典型的错误做法）、
 * {@code capacity}（座位数已废弃）、{@code nextTime}（开课文案已废弃）。</p>
 *
 * <p>★{@code lessonHours} 用 {@code @Min(1) @Max(3)} 硬约束：
 * 它决定一次预约占几个整点格子。填 45 或 90 会让格子对不齐，
 * 而唯一索引 {@code uk_coach_start(coach_id, start_time)} 也就拦不住时段冲突了——
 * <b>这个字段的错误不会立刻报错，只会在很久以后表现为"两个人约到同一个教练同一小时"</b>，
 * 所以必须在入口拦住。</p>
 */
@Data
public class CourseSaveDTO {

    /** 编辑时传 id；null 表示新增 */
    private Long id;

    @NotBlank(message = "课程名称不能为空")
    @Size(max = 50, message = "课程名称不超过 50 字")
    private String name;

    /** SALE 可购买可约课 / CONTENT 展示型课程 */
    private String courseKind;

    /** 分类：燃脂/力量/拉伸/心肺/体态/康复 */
    @NotBlank(message = "类型不能为空")
    @Size(max = 10, message = "类型不超过 10 字")
    private String type;

    /** 一节课几小时，只允许 1/2/3。管理端表单用下拉，不给输入框 */
    @NotNull(message = "请设置一节课的时长（小时）")
    @Min(value = 1, message = "一节课只能是 1/2/3 小时")
    @Max(value = 3, message = "一节课只能是 1/2/3 小时")
    private Integer lessonHours;

    /** 净时长（分钟），展示用 */
    @NotNull(message = "时长不能为空")
    @Min(value = 1, message = "时长必须大于 0")
    private Integer duration;

    @NotNull(message = "消耗不能为空")
    @Min(value = 0, message = "消耗不能为负")
    private Integer calories;

    /** 原价。★BigDecimal 而不是 double：金额不能用浮点 */
    @NotNull(message = "价格不能为空")
    private BigDecimal price;

    /** TIMES 按次 / UNLIMITED 不限次。courseKind=CONTENT 时服务端会强制置 null */
    private String saleType;

    /** 一单给几次。saleType=UNLIMITED 时服务端置 null */
    @Min(value = 1, message = "每单次数至少 1")
    private Integer timesPerBuy;

    /** 标签，逗号分隔字符串 */
    @Size(max = 100, message = "标签不超过 100 字")
    private String tags;

    /** 封面图相对路径：由上传接口返回后回填 */
    @Size(max = 200)
    private String coverImage;

    /** 封面渐变色下标 0~5 */
    @Size(max = 2)
    private String coverColor;

    private String description;

    @Size(max = 100, message = "适合人群不超过 100 字")
    private String suitable;

    /** 上下架：1 上架 / 0 下架。新增时不传按上架处理 */
    @Min(value = 0, message = "状态取 0 或 1")
    @Max(value = 1, message = "状态取 0 或 1")
    private Integer status;

    // ===== 活动页（Banner）=====
    /** 是否上架活动页 */
    private Boolean bannerEnabled;

    /** 活动海报相对路径，如 /uploads/banner/xxx.jpg */
    @Size(max = 200)
    private String bannerImageUrl;

    @Size(max = 50)
    private String bannerTitle;

    @Size(max = 100)
    private String bannerSubtitle;

    private Integer bannerSort;

    /** 可选覆盖；空则默认从当前时间开始、长期展示 */
    private String bannerStartTime;

    private String bannerEndTime;
}
