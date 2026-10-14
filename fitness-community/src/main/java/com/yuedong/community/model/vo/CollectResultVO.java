package com.yuedong.community.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 收藏结果（与前端对齐：collected + 最新收藏数）。
 *
 * <p>★和 {@link LikeResultVO} 分开两个类，不共用一个"互动结果"：
 * 两者字段现在恰好一样，但语义不同，共用之后前端要按 type 猜该刷新哪个计数器。
 * 两个各 15 行的小类比一个带 type 的大类便宜。</p>
 */
@Data
@AllArgsConstructor
public class CollectResultVO {

    /** 本次操作之后是不是收藏状态 */
    private boolean collected;

    /** 最新收藏数（服务端算完直接回，省一次"改完再查"的往返） */
    private Integer collects;
}
