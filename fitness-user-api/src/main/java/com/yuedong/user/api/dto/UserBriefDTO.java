package com.yuedong.user.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户简况 DTO（服务间传输：排行榜补昵称、Feed 流作者快照、管理端订单页补会员昵称）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBriefDTO {

    private Long id;

    private String nickname;

    /** 头像配色下标 */
    private String avatarColor;
}
