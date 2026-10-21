package com.yuedong.community.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 动态视图对象（卡片渲染用）。
 *
 * <p>★三个"当前用户相关"的标记（{@code mine/liked/collected}）由服务层<b>一次批量</b>查出：
 * 未登录时三个都是 false，而不是 null——"没登录"和"没点过"在页面上是同一种表现（都可以点），
 * 用 null 反而要前端多判一次。</p>
 */
@Data
@Builder
public class PostVO {

    private Long id;

    private Long userId;

    /** 作者昵称（发布时快照，改昵称不影响历史动态） */
    private String nickname;

    /** 头像渐变下标（没上传头像时用它兜底） */
    private Integer avatarIdx;

    /** 作者头像访问路径；用户服务暂时不可用时为 null，前端回退渐变色 */
    private String avatarImage;

    private String content;

    /** 配图访问路径列表（如 /uploads/post/xxx.png；空数组=无图） */
    private List<String> images;

    private Integer likes;

    /** 当前用户是否已点赞 */
    private boolean liked;

    private Integer collects;

    /** 当前用户是否已收藏 */
    private boolean collected;

    private Integer comments;

    /**
     * 浏览量。<b>含未入账的 Redis 增量</b>：读的时候把 delta 加上再显示，
     * 所以用户"刚看完就有 +1"的体感是对的，而 DB 那一列晚 30 秒才动没关系。
     */
    private Integer views;

    /** 相对时间文案：刚刚 / X 分钟前 / 昨天 HH:mm / MM-dd */
    private String time;

    private String topic;

    /** PUBLIC / FRIEND / PRIVATE（作者主页要显示"仅好友可见"的小锁标） */
    private String visibility;

    /** 可见性文案，服务层给（★前端不维护字典，加一档可见性只改后端） */
    private String visibilityText;

    /** 是否本人动态（显示"删除"按钮的开关） */
    private boolean mine;
}
