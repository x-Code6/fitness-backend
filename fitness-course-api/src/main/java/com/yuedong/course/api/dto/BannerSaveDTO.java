package com.yuedong.course.api.dto;

import lombok.Data;

/**
 * 管理端活动页编辑参数：只允许改展示信息与排序，来源关系由课程/券/秒杀表单决定。
 */
@Data
public class BannerSaveDTO {

    private Long id;

    private String title;

    private String subtitle;

    private Integer sortNo;

    /** 1 上架 / 0 下架 */
    private Integer status;

    /** 可选覆盖；格式 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd HH:mm，空则保留原值/自动跟随来源 */
    private String startTime;

    private String endTime;
}
