package com.yuedong.community.service;

import com.yuedong.community.model.vo.FollowUserVO;

import java.util.List;

/**
 * 关注/粉丝业务接口。
 */
public interface FollowService {

    /** 关注某人（幂等：重复关注返回成功） */
    void follow(Long userId, Long followeeId);

    /** 取消关注 */
    void unfollow(Long userId, Long followeeId);

    /** 是否已关注 */
    boolean isFollowing(Long userId, Long followeeId);

    /**
     * 两人是否互关 —— 这是"仅好友可见"里"好友"的定义（docs 4.12 K3）。
     *
     * <p>★这里用两次等值 count 而不是先取 {@link #mutualFriendIds} 再 contains：
     * 可见性判定是<b>单点</b>问题（这一条动态能不能给他看），
     * 取全量互关集合是<b>列表</b>问题（好友动态那一页要扫哪些作者）。
     * 单点问题去拉全量，就是"为了查一个人把他所有朋友都请过来"。</p>
     */
    boolean isMutualFollow(Long a, Long b);

    /** 我关注的人 */
    List<FollowUserVO> followingList(Long userId);

    /** 我的粉丝 */
    List<FollowUserVO> fansList(Long userId);

    /**
     * 我与目标用户的共同关注。
     * <p>★旧实现是 Redis SINTER，而 {@code community:following:{id}} 只在关注时写、
     * <b>没有任何重建入口</b> → Redis 一清空，共同关注永远返回空，
     * 但关注列表本身（读 DB）却还是好的 —— 这就是"同一个事实存两处"的必然结果。
     * 现在改为查 DB 两个集合再取交集。</p>
     */
    List<FollowUserVO> commonFollow(Long userId, Long targetId);

    /** 某用户的粉丝 id 列表（发动态写收件箱"推模式"用） */
    List<Long> followerIds(Long userId);

    /** 我关注了谁（关注流与"我关注的人"页用） */
    List<Long> followingIds(Long userId);

    /**
     * 和我互关的人 = "好友"（好友动态那一档可见性要用，docs 4.12 K3）。
     * <p>★不建 {@code t_friend} 表：那会引入"关注/取关要双向同步"的一致性问题。
     * 互关是能从单向关系算出来的<b>只读结论</b>，就该算，不要存一份会腐烂的副本。</p>
     */
    List<Long> mutualFriendIds(Long userId);
}
