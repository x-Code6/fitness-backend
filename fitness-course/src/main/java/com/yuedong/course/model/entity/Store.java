package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 门店实体（PO），对应表 yd_course.t_store。
 * 经纬度用于启动时写入 Redis GEO，实现"附近门店按距离排序"。
 *
 * <p>★v2 两处重要变化：</p>
 * <ol>
 *   <li><b>评分列被删掉</b>。评分是"评价"聚合出来的结果，而评价数据（{@code t_rating}）
 *       属于订单域、落在 yd_order——只有同库才能"写明细 + 更新汇总列"在一个事务里完成。
 *       所以评分汇总放在 yd_order 的 {@code t_store_rating_stat}，本服务要展示评分时
 *       用 Feign 批量取分、失败降级为"暂无评分"（同排行榜批量补昵称的降级套路）。</li>
 *   <li><b>营业时间从一个文案列拆成三个可计算列</b>。旧库只有
 *       {@code open_hours VARCHAR} 存 "07:00-23:00" / "24 小时"，机器读不了；
 *       而取消教练排班之后，可约时段的边界完全靠营业时间算，必须是数字列。</li>
 * </ol>
 *
 * <p>展示文案（{@code openHoursText}）与计算列（{@code openStartHour/openEndHour/is24h}）分开：
 * <b>不要拿展示字段做业务判断</b>。这个教训在本项目里已经出现两次（另一个是
 * {@code t_course.next_time}）。</p>
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

    /** 经度。用 DECIMAL 不用 DOUBLE：浮点有精度误差，6 位小数约 0.1 米 */
    @TableField("lng")
    private BigDecimal lng;

    /** 纬度 */
    @TableField("lat")
    private BigDecimal lat;

    /** 门店地址 */
    @TableField("address")
    private String address;

    /** 门店简介 */
    @TableField("intro")
    private String intro;

    /** 特色标签，逗号分隔（仅展示，检索走 LIKE） */
    @TableField("tags")
    private String tags;

    /** 门店图片相对路径（管理端上传后写入） */
    @TableField("image")
    private String image;

    /** 营业开始整点（0~23），教练可约时段的下边界 */
    @TableField("open_start_hour")
    private Integer openStartHour;

    /** 营业结束整点（0~24），教练可约时段的上边界 */
    @TableField("open_end_hour")
    private Integer openEndHour;

    /** 1 全天营业：此时忽略上面两个整点列，可约区间取 0~24 */
    @TableField("is_24h")
    private Integer is24h;

    /** 营业时间展示文案，如"07:00-23:00""24 小时"。只给用户看，不参与任何计算 */
    @TableField("open_hours_text")
    private String openHoursText;

    /** 联系电话 */
    @TableField("phone")
    private String phone;

    /** 状态：1 营业中 / 0 已关闭 */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
