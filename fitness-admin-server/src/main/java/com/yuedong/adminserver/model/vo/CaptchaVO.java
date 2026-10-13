package com.yuedong.adminserver.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 图形验证码视图（captchaId + base64 图片）。
 */
@Data
@Builder
public class CaptchaVO {

    /** 验证码 id（登录时回传） */
    private String captchaId;

    /** base64 PNG 图片 */
    private String image;
}
