package com.yuedong.user.controller;

import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.user.model.vo.MessageVO;
import com.yuedong.user.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内消息接口（需登录）。
 *
 * <p>消息由各业务域事件经 MQ 异步写入（见 NotifyEventConsumer）。</p>
 */
@RestController
@RequestMapping("/message")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /** 消息列表（倒序分页） */
    @GetMapping("/list")
    public Result<PageDTO<MessageVO>> list(@RequestParam(defaultValue = "1") Integer page,
                                           @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(messageService.list(UserContext.getUserId(), page, size));
    }

    /** 未读消息数（首页红点） */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(messageService.unreadCount(UserContext.getUserId()));
    }

    /** 标记单条已读 */
    @PostMapping("/read")
    public Result<Void> read(@RequestParam Long id) {
        messageService.markRead(UserContext.getUserId(), id);
        return Result.ok();
    }

    /** 全部标记已读 */
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        messageService.markAllRead(UserContext.getUserId());
        return Result.ok();
    }
}
