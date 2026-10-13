package com.yuedong.point.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 积分流水条目。
 */
@Data
@Builder
@AllArgsConstructor
public class PointsLogVO {

    private Long id;

    private String title;

    /** 积分变动（正加负减） */
    private Integer points;

    private String time;
}
