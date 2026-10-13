package com.yuedong.point.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会员积分汇总实体（PO），对应表 yd_point.t_member_points。
 *
 * <p>每用户一行（user_id 唯一），余额与累计打卡在这里维护；
 * 明细流水在 t_points_log，两者同事务写入。</p>
 */
@Data
@TableName("t_member_points")
public class MemberPoints {

    /** 用户 id（业务主键，非自增） */
    @TableId(value = "user_id", type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private Long userId;

    /** 积分余额 */
    @TableField("balance")
    private Integer balance;

    /** 累计打卡天数 */
    @TableField("total_checkin")
    private Integer totalCheckin;

    /** 连续打卡天数（每次打卡时由 Bitmap 反推后写入） */
    @TableField("continuous_days")
    private Integer continuousDays;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
