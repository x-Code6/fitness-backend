package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动实体（PO），对应表 yd_course.t_seckill_activity。
 *
 * <p>状态机：0 待开始 → 1 进行中（定时任务到点把库存预热进 Redis）→ 2 已结束。</p>
 * <p>remain（DB 剩余库存）与 Redis 库存的关系：活动开始时 DB.remain → Redis；
 * 抢购期间 Redis 是扣减的"第一现场"（Lua 原子操作在订单服务），
 * 消费端建单成功后再异步扣减 DB.remain，做到"Redis 抗并发、DB 最终一致"。</p>
 */
@Data
@TableName("t_seckill_activity")
public class SeckillActivity {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 秒杀的课程 */
    @TableField("course_id")
    private Long courseId;

    /** 秒杀价 */
    @TableField("seckill_price")
    private BigDecimal seckillPrice;

    /** 总名额 */
    @TableField("total")
    private Integer total;

    /** DB 剩余名额 */
    @TableField("remain")
    private Integer remain;

    /** 活动开始时间（到点由定时任务预热库存） */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** 活动结束时间（Redis 资格集合 TTL 基准） */
    @TableField("end_time")
    private LocalDateTime endTime;

    /** 0 待开始 / 1 进行中 / 2 已结束 */
    @TableField("status")
    private Integer status;
}
