package com.yuedong.community.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Feed 流分页结果（结构对齐前端：{ list, hasMore, page }）。
 */
@Data
@Builder
@AllArgsConstructor
public class FeedResultVO {

    private List<PostVO> list;

    private boolean hasMore;

    private Integer page;
}
