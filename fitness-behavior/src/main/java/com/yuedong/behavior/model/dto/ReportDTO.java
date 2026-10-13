package com.yuedong.behavior.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * 埋点批量上报请求（前端 SDK 攒批：满 5 条或每 3 秒上报一次）。
 */
@Data
public class ReportDTO {

    /** 设备 id（前端 localStorage 生成 UUID；匿名用户的行为身份） */
    @NotBlank(message = "deviceId 不能为空")
    private String deviceId;

    /** 事件列表（1~20 条） */
    @Valid
    @NotEmpty(message = "events 不能为空")
    private List<Event> events;

    /**
     * 单条行为事件。
     */
    @Data
    public static class Event {

        /** 事件唯一 id（消费端幂等键） */
        @NotBlank(message = "eventId 不能为空")
        private String eventId;

        /** 事件类型：page_view / course_click / seckill_click / checkin_click */
        @NotBlank(message = "事件类型不能为空")
        @Pattern(regexp = "page_view|course_click|seckill_click|checkin_click",
                message = "未知的事件类型")
        private String type;

        /** 事件目标（课程 id 等；page_view 无目标） */
        private Long targetId;

        /** 客户端时间戳（毫秒） */
        private Long ts;
    }
}
