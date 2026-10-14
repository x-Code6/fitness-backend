package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 课程实体（PO），对应表 yd_course.t_course。
 *
 * <p>★v2 的语义变化：课程从"一场有时刻、有座位的活动"变成<b>"可购买的商品 / 可展示的内容"</b>。
 * 所以旧实体上的这批列全部去掉了，去掉的理由各不同：</p>
 * <ul>
 *   <li>{@code capacity} / {@code booked}：座位数与已约人数。团课取消后，稀缺资源是
 *       "教练的时间"，容量判断落到 {@code yd_order.t_coach_busy} 的时段占用上；</li>
 *   <li>{@code next_time}（"今天 19:00"这种文案）：时刻不再属于课程；
 *       ⚠️ 旧代码里订单的 {@code book_time} 直接取这列，才导致秒杀单显示"预约制"这种文案；</li>
 *   <li>{@code store_id} / {@code store_name}：一门课可以在多家门店售卖，
 *       关系移到 {@link CourseStore}（那里还带两层时间窗）；</li>
 *   <li>{@code coach} / {@code coach_title}：教练成为独立实体
 *       （见 {@link Coach} 与 {@link CoachCourse}），字符串列无法表达"一课多师、一师多课"。</li>
 * </ul>
 *
 * <p>tags 在库里仍是逗号分隔字符串（如"燃脂,新手友好"），转 List 的动作在 VO 组装层做——
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

    /**
     * 课程形态：SALE 可购买可约教练的线下课 / CONTENT 展示型课程（看视频 + 评论，无需购买）。
     * <p>两类课共用一张表，是因为共用字段占九成；差异只体现在"能不能买""要不要约教练"，
     * 用一个类型位判断足够，拆两张表就要维护两套 CRUD 和两套缓存。</p>
     */
    @TableField("course_kind")
    private String courseKind;

    /** 分类标签：燃脂/力量/拉伸/心肺/体态/康复。v2 里它只是分类，不再有"团课/私教"语义 */
    @TableField("type")
    private String type;

    /**
     * 一节课占几个整点小时格子，只允许 1/2/3。
     * <p>★这个列是时段模型的基石：预约以"节"为最小单位不可拆分，且起点必须整点，
     * "同教练同时段冲突"才能退化成"格子的唯一索引撞不撞"。
     * 一旦允许 45/90 分钟，格子对不齐，就得改写成立区间重叠判断 + 行锁。</p>
     */
    @TableField("lesson_hours")
    private Integer lessonHours;

    /** 课程净时长（分钟），仅展示用。45 分钟的课仍占 1 个整点格子 */
    @TableField("duration")
    private Integer duration;

    /** 预计消耗千卡 */
    @TableField("calories")
    private Integer calories;

    /** 原价（单节/单次价）。CONTENT 课程为 0.00 */
    @TableField("price")
    private BigDecimal price;

    /** 售卖形态：TIMES 按次 / UNLIMITED 有效期内不限次。CONTENT 为 null */
    @TableField("sale_type")
    private String saleType;

    /** 买一单给几次（仅 TIMES 有值）；UNLIMITED 为 null。★一单几张凭证就乘几次，凭证不合并 */
    @TableField("times_per_buy")
    private Integer timesPerBuy;

    /** 标签，逗号分隔 */
    @TableField("tags")
    private String tags;

    /** 封面图相对路径（管理端上传后写入）；为 null 时前端回退 coverColor 渐变 */
    @TableField("cover_image")
    private String coverImage;

    /** 封面渐变色下标 0~5 */
    @TableField("cover_color")
    private String coverColor;

    /** 课程介绍（详情长文） */
    @TableField("description")
    private String description;

    /** 适合人群 */
    @TableField("suitable")
    private String suitable;

    /** 状态：1 上架 / 0 下架。下架不进列表；详情缓存重建时若发现已下架会清缓存并写空值标记 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
