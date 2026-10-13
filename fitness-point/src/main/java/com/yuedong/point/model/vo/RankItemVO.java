package com.yuedong.point.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 排行榜条目（字段与前端 Mock 对齐：rank/nickname/points/me）。
 */
@Data
@Builder
@AllArgsConstructor
public class RankItemVO {

    /** 名次（1 起） */
    private Integer rank;

    /** 用户 id（前端可用） */
    private Long userId;

    private String nickname;

    private Integer points;

    /** 是否当前登录用户 */
    private boolean me;
}
