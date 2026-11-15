package com.yuedong.behavior.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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

    /** 事件列表（1~20 条）。★上限必须写：这是匿名可调的埋点口，没有上限就变成 DDOS 放大器 */
    @Valid
    @NotEmpty(message = "events 不能为空")
    @Size(max = 20, message = "单次最多上报 20 条事件")
    private List<BehaviorEventDTO> events;
}
