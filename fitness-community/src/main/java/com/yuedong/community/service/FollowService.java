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

    /** 我关注的人 */
    List<FollowUserVO> followingList(Long userId);

    /** 我的粉丝 */
    List<FollowUserVO> fansList(Long userId);

    /** 我与目标用户的共同关注（Redis SINTER） */
    List<FollowUserVO> commonFollow(Long userId, Long targetId);

    /** 某用户的粉丝 id 列表（发动态写收件箱"推模式"用） */
    List<Long> followerIds(Long userId);
}
