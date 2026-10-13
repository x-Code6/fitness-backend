package com.yuedong.behavior.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 热门课程条目（首页推荐排序的数据源）。
 */
@Data
@AllArgsConstructor
public class HotCourseVO {

    /** 课程 id */
    private Long courseId;

    /** 点击热度（当月累计点击次数） */
    private Long clicks;
}
