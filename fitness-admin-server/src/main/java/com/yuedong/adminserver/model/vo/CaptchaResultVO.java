package com.yuedong.adminserver.model.vo;

/**
 * 图形验证码生成结果：code 存 Redis，imageBase64 返回给前端。
 */
public record CaptchaResultVO(String code, String imageBase64) {
}
