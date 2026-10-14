package com.yuedong.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.community.model.entity.Follow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 关注关系 Mapper。 */
public interface FollowMapper extends BaseMapper<Follow> {

    /**
     * 查"和我互关的人"，即"好友动态"要看的作者集合（docs 4.12 K3）。
     *
     * <p>★<b>自连接</b>而不是"先查我关注的人，再逐个判断他有没有回关"：
     * 后者是 N+1；前者一条 SQL，两侧都走索引
     * （{@code uk_follow_pair(user_id, followee_id)} 与 {@code idx_followee(followee_id, user_id)}），
     * 而且<b>覆盖索引</b>就够，一次回表都不需要。</p>
     *
     * <p>★为什么不建 {@code t_friend} 表：建了就要处理"关注/取关时的双向同步"，
     * 同步失败会出现"我显示他是好友、他不显示"。
     * 互关是<b>可以从单向关系推出来的只读结论</b>，就该在查的时候算，不要落一份会腐烂的副本。</p>
     */
    @Select("SELECT a.followee_id" +
            "  FROM t_follow a" +
            "  JOIN t_follow b ON b.user_id = a.followee_id AND b.followee_id = a.user_id" +
            " WHERE a.user_id = #{userId}")
    List<Long> mutualFolloweeIds(@Param("userId") Long userId);
}
