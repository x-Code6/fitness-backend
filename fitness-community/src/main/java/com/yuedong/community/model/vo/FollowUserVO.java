package com.yuedong.community.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 关注/粉丝/共同关注列表条目（昵称经 Feign 批量补齐）。 */
@Data
@Builder
@AllArgsConstructor
public class FollowUserVO {

    private Long userId;

    private String nickname;

    /** 头像渐变下标 */
    private Integer avatarIdx;
}
