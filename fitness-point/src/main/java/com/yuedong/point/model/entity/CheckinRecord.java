package com.yuedong.point.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日打卡记录，对应表 yd_point.t_checkin_record。
 *
 * <p>★这张表的存在就是为了把"打卡明细"从 Redis 里搬出来（docs D16 / 核查 P0-5）：
 * 旧实现只有 {@code point:check:{yyyyMM}:{userId}} 一个位图，Redis 一清空，
 * 用户当月打过哪些天、连续多少天全部无从解释。现在：
 * <b>DB 是真相，位图是能从真相重建的展示缓存</b>。</p>
 *
 * <p>★判重靠 {@code uk_user_date(user_id, check_date)} 唯一索引而不是"先 GETBIT 再 SETBIT"：
 * 后者两次命令之间有窗口，并发双击会加两次分（P1-7）。
 * 唯一索引是数据库级保证，不需要 Lua、不需要分布式锁 ——
 * 这正是本项目"必须防"那一档的标准解法。</p>
 */
@Data
@TableName("t_checkin_record")
public class CheckinRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** 打卡日期。★只到天，一天一条，靠唯一索引锁住 */
    @TableField("check_date")
    private LocalDate checkDate;

    /** 打卡时刻（几点几分，日历上不显示但排查问题要用） */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
