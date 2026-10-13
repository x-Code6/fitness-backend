package com.yuedong.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.community.model.entity.Post;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 动态表 Mapper：计数用原子 UPDATE 维护（点赞数/评论数）。
 */
public interface PostMapper extends BaseMapper<Post> {

    /** 评论数 ±1（delta 传 +1 / -1） */
    @Update("UPDATE t_post SET comment_count = GREATEST(comment_count + #{delta}, 0) " +
            "WHERE id = #{postId}")
    int changeCommentCount(@Param("postId") Long postId, @Param("delta") int delta);

    /** 点赞数 ±1 */
    @Update("UPDATE t_post SET like_count = GREATEST(like_count + #{delta}, 0) " +
            "WHERE id = #{postId}")
    int changeLikeCount(@Param("postId") Long postId, @Param("delta") int delta);
}
