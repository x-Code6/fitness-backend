package com.yuedong.community.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

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

    /**
     * 配图访问路径列表（先调 /file/user/upload/image 上传，再把返回的 url 放进来）。
     * 为空即纯文本动态；落库存 csv，读出来再拆成列表。
     */
    @Size(max = 9, message = "最多上传 9 张配图")
    private List<@Size(max = 100, message = "配图路径过长") String> images;
}
