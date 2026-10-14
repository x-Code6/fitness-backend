package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教练实体，对应表 yd_course.t_coach。
 *
 * <p>为什么 v2 要把教练做成实体（旧库里只有 {@code t_course.coach} 一个名字字符串）：</p>
 * <ul>
 *   <li>预约的稀缺资源是<b>教练的时间</b>，不是教室座位。要判断"这个时段能不能约"，
 *       必须先有"教练"这个可引用 id，占用表 {@code t_coach_busy} 才有东西可关联；</li>
 *   <li>一个教练教多门课、一门课有多个教练 → 必须多对多（见 {@link CoachCourse}），
 *       靠字符串列无法表达；</li>
 *   <li>教练要能被停用、要有头像/资质/可约时间，这些都是实体属性而不是课程属性。</li>
 * </ul>
 *
 * <p>★{@code workStartHour/workEndHour/workDays} 三列是"取消教练排班"这个决定的替代品：
 * 系统不预生成时段行，只记录这位教练原则上几点到几点、周几能约，
 * 可约时刻由预约侧查询实时计算（见 {@code CoachService} 与后续 order 域的时段接口）。</p>
 */
@Data
@TableName("t_coach")
public class Coach {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 隶属门店（同库逻辑外键 → t_store.id）。教练固定在一家店，跨店巡回暂未支持 */
    @TableField("store_id")
    private Long storeId;

    /** 教练姓名。同店不重名由唯一索引 uk_store_name 兜底 */
    @TableField("name")
    private String name;

    /** 资质文案，如 NSCA-CPT / RYT200。纯展示，系统不解析其含义 */
    @TableField("title")
    private String title;

    /** 简介 */
    @TableField("intro")
    private String intro;

    /**
     * 联系电话。
     * <p>⚠️ 这个字段只能出现在管理端，<b>绝不能进 C 端任何 VO</b>——
     * 教练手机号属于个人信息，前台页面暴露出去就是合规问题。</p>
     */
    @TableField("phone")
    private String phone;

    /** 照片相对路径（管理端上传后写入）。为空时前端回退用 avatarColor */
    @TableField("avatar_image")
    private String avatarImage;

    /** 头像配色下标 0~5，与前端色盘一致 */
    @TableField("avatar_color")
    private String avatarColor;

    /** 性别：1 男 / 2 女 / null 未填。仅用于前端筛选 */
    @TableField("gender")
    private Integer gender;

    /** 每日可约起始整点（0~23）。与门店营业时间取交集后才是要展示的区间 */
    @TableField("work_start_hour")
    private Integer workStartHour;

    /** 每日可约结束整点（1~24）。24 表示到当天午夜 */
    @TableField("work_end_hour")
    private Integer workEndHour;

    /** 可约星期，逗号分隔的 1~7（1=周一）。当天不在其中则该教练全天不可约 */
    @TableField("work_days")
    private String workDays;

    /** 状态：1 可约 / 0 停用。停用后不出现在可约教练列表，但历史预约记录仍保留其名字快照 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
