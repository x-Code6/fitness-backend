package com.yuedong.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.course.model.entity.CourseComment;

/**
 * 展示课评论 Mapper。
 *
 * <p>只用通用条件就够：一级列表走 {@code idx_course_root(course_id, root_id, id)}，
 * 某一级下的回复走 {@code idx_root(root_id, id)}，删一条是一句带归属条件的 UPDATE。
 * ★没有"一条 SQL 把一级和回复一起捞出来"的写法——那要自连接，
 * 而两级数据用两次简单查询更好读，也天然限制了"回复数把结果集撑爆"。</p>
 */
public interface CourseCommentMapper extends BaseMapper<CourseComment> {
}
