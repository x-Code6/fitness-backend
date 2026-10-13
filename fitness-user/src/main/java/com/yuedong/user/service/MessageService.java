package com.yuedong.user.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.user.model.vo.MessageVO;

/**
 * 站内消息业务接口（消息由各业务域事件经 MQ 异步写入，见 NotifyEventConsumer）。
 */
public interface MessageService {

    /** 当前用户的消息列表（倒序分页） */
    PageDTO<MessageVO> list(Long userId, Integer page, Integer size);

    /** 未读消息数（首页红点） */
    long unreadCount(Long userId);

    /** 标记单条已读（只能标记自己的消息） */
    void markRead(Long userId, Long id);

    /** 全部标记已读 */
    void markAllRead(Long userId);
}
