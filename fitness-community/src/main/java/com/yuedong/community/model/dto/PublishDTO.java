package com.yuedong.community.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布动态请求参数。
 */
@Data
public class PublishDTO {

    @NotBlank(message = "说点什么再发布吧")
    @Size(max = 500, message = "动态最长 500 字")
    private String content;

    @Size(max = 20, message = "话题过长")
    private String topic;
}
