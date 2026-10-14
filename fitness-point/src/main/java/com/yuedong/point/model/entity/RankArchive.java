package com.yuedong.point.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 月度排行榜归档，对应表 yd_point.t_rank_archive。
 *
 * <p>★<b>单表 + season 字段</b>，不是"每月一张表"（docs D17）：
 * 分表要面对"一年后 12 张同构表、动态拼表名让 {@code @TableName} 失效、
 * 跨月趋势要 UNION、新月份的表谁来建、建失败怎么办"——
 * 这些运营成本在"每月几千行"的量级上纯属自找。</p>
 *
 * <p>主键是 {@code (season, user_id)}：同一月同一人只有一行，
 * 归档任务因此<b>重跑安全</b>（{@code INSERT ... ON DUPLICATE KEY UPDATE} 覆盖同名次即可），
 * 不需要任何锁。</p>
 */
@Data
@TableName("t_rank_archive")
public class RankArchive {

    /** 归档月份 yyyyMM（联合主键之一，所以实体里它是普通字段而不是"表名后缀"） */
    @TableId(value = "season", type = IdType.INPUT)
    private String season;

    @TableField("user_id")
    private Long userId;

    /** 该月最终积分 */
    @TableField("score")
    private Integer score;

    /**
     * 归档时的名次。<b>存下来而不是每次查再算</b>：
     * 名次一旦结算就是历史事实，将来加"并列按打卡天数二次排序"这类规则时，
     * 老榜单不会被新规则重排。
     */
    @TableField("rank_no")
    private Integer rankNo;

    /** 该月累计打卡天数 */
    @TableField("total_checkin")
    private Integer totalCheckin;

    /** 该月最长连续打卡 */
    @TableField("continuous_best")
    private Integer continuousBest;

    @TableField("archived_at")
    private LocalDateTime archivedAt;
}
