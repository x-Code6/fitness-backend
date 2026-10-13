package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 门店实体（PO），对应表 yd_course.t_store。
 * 经纬度用于启动时写入 Redis GEO，实现"附近门店按距离排序"。
 */
@Data
@TableName("t_store")
public class Store {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 门店名 */
    @TableField("name")
    private String name;

    /** 经度（GCJ-02，与前端地图一致） */
    @TableField("lng")
    private BigDecimal lng;

    /** 纬度 */
    @TableField("lat")
    private BigDecimal lat;

    /** 门店地址 */
    @TableField("address")
    private String address;

    /** 评分（0~5，一位小数） */
    @TableField("rating")
    private BigDecimal rating;

    /** 特色标签，逗号分隔 */
    @TableField("tags")
    private String tags;

    /** 营业时间（如 07:00-23:00） */
    @TableField("open_hours")
    private String openHours;

    /** 联系电话 */
    @TableField("phone")
    private String phone;

    /** 教练数量 */
    @TableField("coaches")
    private Integer coaches;
}
