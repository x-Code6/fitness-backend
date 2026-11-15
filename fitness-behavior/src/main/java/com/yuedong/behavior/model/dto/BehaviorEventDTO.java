package com.yuedong.behavior.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

/**
 * 单条行为事件。
 */
@Data
public class BehaviorEventDTO {

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

    /**
     * 事件扩展属性，例 {@code {"from":"home_banner","tab":"燃脂"}}。
     * <p>★不校验内容、不限键名：埋点的价值恰恰在于属性会随业务加，
     *   每次加一个来源标记都要改后端校验就没人愿意埋点了。
     *   限制放在<b>体积</b>上：整批 20 条、单条 props 原样转 JSON 落库，
     *   真正的护栏是埋点接口在网关侧的限流（本期未做，见 docs 附 C）。</p>
     */
    private Map<String, Object> props;
}
