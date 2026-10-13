package com.yuedong.user.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内消息实体（PO），对应表 yd_user.t_message。
 *
 * <p>来源：各业务服务（订单/积分/社区）把"发生了什么"发到 MQ
 * （ex.notify.exchange），本服务的消费者翻译成站内消息落库。</p>
 */
@Data
@TableName("t_message")
public class Message {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 接收人 */
    @TableField("user_id")
    private Long userId;

    /** 消息标题 */
    @TableField("title")
    private String title;

    /** 消息内容 */
    @TableField("content")
    private String content;

    /** 消息类型：BOOKING 预约 / POINT 积分 / INTERACTION 互动 */
    @TableField("msg_type")
    private String msgType;

    /** 0 未读 / 1 已读 */
    @TableField("is_read")
    private Integer isRead;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
