package com.yuedong.adminserver.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 会员管理页行视图（user 基础信息 + point 积分聚合）。
 */
@Data
@Builder
public class MemberVO {

    private Long id;

    private String nickname;

    private String phone;

    /** 0=正常 1=禁用 */
    private Integer status;

    /** yyyy-MM-dd HH:mm */
    private String createdAt;

    /** 积分余额（point 服务聚合；不可用时为 0） */
    private Integer points;

    /** 累计打卡天数（point 服务聚合） */
    private Integer totalCheckin;
}
