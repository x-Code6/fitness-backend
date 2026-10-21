package com.yuedong.community.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端社区动态行。
 *
 * <p>比用户端 {@code PostVO} 多出 {@code status}：管理端要能区分
 * “正常 / 作者删除 / 审核隐藏”，否则运营看不到被隐藏的内容，也无法恢复。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommunityAdminDTO {

    private Long id;

    private Long userId;

    private String userName;

    /** 头像渐变下标；管理端列表只给色块，避免每行再查一次用户服务 */
    private Integer avatarIdx;

    private String content;

    /** 配图访问路径列表 */
    private List<String> images;

    private String topic;

    private String visibility;

    private String visibilityText;

    private Integer likeCount;

    private Integer collectCount;

    private Integer commentCount;

    private Integer viewCount;

    /** 0 作者删除 / 1 正常 / 2 审核隐藏 */
    private Integer status;

    private String statusText;

    private LocalDateTime createdAt;
}
