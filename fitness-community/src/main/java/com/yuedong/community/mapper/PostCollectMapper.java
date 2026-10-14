package com.yuedong.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.community.model.entity.PostCollect;

/**
 * 收藏 Mapper。
 *
 * <p>只用通用条件即可：判重用 {@code selectOne(post_id,user_id)}、
 * 取消用 {@code deleteById}、我的列表用游标条件查询。
 * ★一人一藏的最终防线是 {@code uk_post_user_collect} 唯一索引，
 * 并发双击时第二次插入撞键，应用层按"已收藏"返回。</p>
 */
public interface PostCollectMapper extends BaseMapper<PostCollect> {
}
