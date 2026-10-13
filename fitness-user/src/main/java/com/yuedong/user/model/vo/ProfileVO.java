package com.yuedong.user.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户资料视图对象（VO，对外输出，绝不包含 password）。
 *
 * <p>VO 与实体分离的意义：实体跟随表结构变化，VO 跟随接口契约变化，
 * 两者解耦后互不牵连；同时从编译层面防止敏感字段外泄。</p>
 */
@Data
@Builder
public class ProfileVO {

    private Long id;

    /** 脱敏后的手机号，如 138****8819 */
    private String phone;

    private String nickname;

    /** 头像配色下标（前端按此取渐变色） */
    private String avatarColor;

    private LocalDateTime createdAt;
}
