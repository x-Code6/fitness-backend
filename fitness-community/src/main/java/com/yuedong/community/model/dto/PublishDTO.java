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

    /**
     * 可见性：PUBLIC（默认）/ FRIEND（仅互关好友）/ PRIVATE（私密）。
     * <p>★不校验枚举值由服务层做（服务层要按它决定<b>要不要扇出到粉丝收件箱</b>，
     * 传进来一个错值不能默默当公开处理——那是隐私事故，不是显示 bug）。</p>
     */
    private String visibility;

    /** 配图相对路径 csv。一期管理端上传能力接入后才可用，为空即纯文本动态 */
    @Size(max = 200, message = "配图过多")
    private String images;
}
