package com.yuedong.community.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 动态视图对象（字段与前端 PostCard 完全对齐）。
 */
@Data
@Builder
public class PostVO {

    private Long id;

    private Long userId;

    /** 作者昵称（发布时快照） */
    private String nickname;

    /** 头像渐变下标（前端色盘） */
    private Integer avatarIdx;

    private String content;

    /** 配图（渐变下标列表；空数组=无图） */
    private List<Integer> images;

    private Integer likes;

    /** 当前用户是否已点赞（未登录恒为 false） */
    private boolean liked;

    private Integer comments;

    private String topic;

    /** 相对时间文案：刚刚 / X 分钟前 / 昨天 HH:mm / MM-dd */
    private String time;

    /** 是否本人动态（前端显示"我的"角标） */
    private boolean mine;
}
