package com.yuedong.community.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态收藏实体，对应表 yd_community.t_post_collect。
 *
 * <p>★<b>为什么不和点赞合成一张带 type 的表</b>：
 * 合并之后唯一键必须变成 {@code (post_id, user_id, type)} 才对（否则"点过赞就收藏不了"），
 * 计数列要按 type 分别回退、取消动作要带 type 条件、"我的"里两个 tab 也各要一个 type 参数——
 * <b>省一张表，换来四处都要多一个判断</b>。而且语义本就不同：
 * 点赞是"我给个正反馈"，收藏是"我要留着以后再看"，热度公式里权重也差 2.5 倍。</p>
 */
@Data
@TableName("t_post_collect")
public class PostCollect {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("post_id")
    private Long postId;

    @TableField("user_id")
    private Long userId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
