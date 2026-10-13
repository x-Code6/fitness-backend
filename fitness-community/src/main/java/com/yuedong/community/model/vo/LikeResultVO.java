package com.yuedong.community.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 点赞结果（与前端对齐：liked + 最新点赞数）。
 */
@Data
@AllArgsConstructor
public class LikeResultVO {

    private boolean liked;

    private Integer likes;
}
