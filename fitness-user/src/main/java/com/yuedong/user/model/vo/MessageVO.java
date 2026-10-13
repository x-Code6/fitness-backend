package com.yuedong.user.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 站内消息视图（用户端消息中心）。
 */
@Data
@Builder
public class MessageVO {

    private Long id;

    /** BOOKING/POINT/INTERACTION */
    private String msgType;

    private String title;

    private String content;

    /** 0=未读 1=已读 */
    private Integer isRead;

    /** yyyy-MM-dd HH:mm */
    private String time;
}
