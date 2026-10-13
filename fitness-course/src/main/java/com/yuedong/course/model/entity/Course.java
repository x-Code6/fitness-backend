package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程实体（PO），对应表 yd_course.t_course。
 *
 * <p>tags 在库里是逗号分隔字符串（如"燃脂,新手友好"），转 List 的动作在 VO 组装层做——
 * 简单场景不引入 JSON 类型处理器，保持透明。</p>
 */
@Data
@TableName("t_course")
public class Course {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 课程名 */
    @TableField("name")
    private String name;

    /** 课程类型：团课/私教/瑜伽/骑行/操课 */
    @TableField("type")
    private String type;

    /** 教练名 */
    @TableField("coach")
    private String coach;

    /** 教练资质，如 NSCA-CPT */
    @TableField("coach_title")
    private String coachTitle;

    /** 时长（分钟） */
    @TableField("duration")
    private Integer duration;

    /** 消耗（千卡） */
    @TableField("calories")
    private Integer calories;

    /** 容量 */
    @TableField("capacity")
    private Integer capacity;

    /** 已约人数（占名额用原子 UPDATE 维护） */
    @TableField("booked")
    private Integer booked;

    /** 原价 */
    @TableField("price")
    private java.math.BigDecimal price;

    /** 标签，逗号分隔 */
    @TableField("tags")
    private String tags;

    /** 所属门店 id（t_store） */
    @TableField("store_id")
    private Long storeId;

    /** 门店名（冗余字段，避免列表页每次 join 门店表） */
    @TableField("store_name")
    private String storeName;

    /** 下一次开课时间（展示文案，如"今天 19:00"） */
    @TableField("next_time")
    private String nextTime;

    /** 课程介绍 */
    @TableField("description")
    private String description;

    /** 适合人群 */
    @TableField("suitable")
    private String suitable;

    /** 状态：1 上架 / 0 下架 */
    @TableField("status")
    private Integer status;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
