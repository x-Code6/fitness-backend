package com.yuedong.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.model.PageDTO;
import com.yuedong.user.mapper.MessageMapper;
import com.yuedong.user.model.entity.Message;
import com.yuedong.user.model.vo.MessageVO;
import com.yuedong.user.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 站内消息实现。
 */
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MessageMapper messageMapper;

    @Override
    public PageDTO<MessageVO> list(Long userId, Integer page, Integer size) {
        Page<Message> result = messageMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 20 : size),
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getUserId, userId)
                        .orderByDesc(Message::getId));
        List<MessageVO> rows = result.getRecords().stream()
                .map(this::toVO)
                .toList();
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public long unreadCount(Long userId) {
        return messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getUserId, userId)
                .eq(Message::getIsRead, 0));
    }

    @Override
    public void markRead(Long userId, Long id) {
        Message patch = new Message();
        patch.setIsRead(1);
        // 只允许标记自己的消息（WHERE 里带 userId）
        messageMapper.update(patch, new LambdaQueryWrapper<Message>()
                .eq(Message::getId, id)
                .eq(Message::getUserId, userId));
    }

    @Override
    public void markAllRead(Long userId) {
        Message patch = new Message();
        patch.setIsRead(1);
        messageMapper.update(patch, new LambdaQueryWrapper<Message>()
                .eq(Message::getUserId, userId)
                .eq(Message::getIsRead, 0));
    }

    private MessageVO toVO(Message m) {
        return MessageVO.builder()
                .id(m.getId())
                .msgType(m.getMsgType())
                .title(m.getTitle())
                .content(m.getContent())
                .isRead(m.getIsRead())
                .time(m.getCreatedAt() == null ? null : m.getCreatedAt().format(TIME_FMT))
                .build();
    }
}
