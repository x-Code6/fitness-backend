package com.yuedong.course.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端"课程 × 门店时间窗"分页行（{@code t_course_store} 一整行 + 两个名字 + 一个派生状态）。
 *
 * <p>★这一行的存在就是 v2 "两层时间"的管理界面：一行同时回答
 * <b>什么时候能买</b>（sellStart/sellEnd）和<b>买完什么时候之前要用完</b>（validType 三选一）。
 * 两者在表里是四列而不是一列区间，因为校验时机完全不同：前者拦在下单前，
 * 后者只在生成凭证时用来算 {@code t_entitlement.validUntil}。合并成一列就会做出
 * "停售了但已买的人还能约课"这种谁也说不清的语义。</p>
 *
 * <p>{@code onSaleNow} 是<b>算出来的</b>，库里没有这一列：
 * {@code status==1 且（没有停售时间 或 停售时间还没到）}。留着它是因为列表上"在售/已停售"
 * 是运营最先扫的一眼，让前端各算一遍会分成三份口径。</p>
 */
@Data
@Builder
public class CourseWindowAdminDTO {

    /** t_course_store 主键（保存时回填） */
    private Long id;

    private Long courseId;

    private String courseName;

    private Long storeId;

    private String storeName;

    /** ① 开售时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime sellStartTime;

    /** ① 停售时间；null = 长期在售（前端表单上"forever"这个开关就是它） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime sellEndTime;

    /** ② FOREVER / DAYS / RANGE */
    private String validType;

    /** validType=DAYS 时有值 */
    private Integer validDays;

    /** validType=RANGE 时有值 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime useStartTime;

    /** validType=RANGE 时有值 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    private LocalDateTime useEndTime;

    /** 可售份数；null = 不限 */
    private Integer stock;

    /** 1 在售 / 0 下架 */
    private Integer status;

    /** 派生：当前是否在售（见类注释） */
    private Boolean onSaleNow;
}
