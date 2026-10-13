package com.yuedong.course.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 视频进度上报请求参数（前端节流上报：每推进 20% 调一次）。
 */
@Data
public class VideoProgressDTO {

    /** 视频 id */
    @NotNull(message = "视频 id 不能为空")
    private Long videoId;

    /** 播放进度百分比 0~100 */
    @NotNull(message = "进度不能为空")
    @Min(value = 0, message = "进度不能小于 0")
    @Max(value = 100, message = "进度不能超过 100")
    private Integer progress;
}
