package com.yuedong.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评分汇总统计（门店 / 课程 / 教练三张表结构完全一样）。
 *
 * <p>★只存<b>不可重建的原始量</b>：分数和与条数。<b>平均分和贝叶斯分都不落库</b>：
 * 展示用算术平均、排序用贝叶斯 {@code (C*M + Σ) / (C + n)}，
 * 而 C（可信样本数）与 M（全站均值）是可调参数——
 * 存成列就锁死了这两个参数，调一次要刷全表。
 * 所以现在只在读取时现算，代价是每次多两个除法，换来的是"改参数不用改数据"。</p>
 */
@Data
@TableName("t_store_rating_stat")
public class StoreRatingStat {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("store_id")
    private Long targetId;

    /** 所有正常评价的分数之和。★删除评价时要原子回退，不是读-改-写 */
    @TableField("rating_sum")
    private Integer ratingSum;

    @TableField("rating_count")
    private Integer ratingCount;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
