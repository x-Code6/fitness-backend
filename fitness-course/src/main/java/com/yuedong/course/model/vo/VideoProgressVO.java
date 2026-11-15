package com.yuedong.course.model.vo;

/**
 * 视频进度上报结果：是否完课 + 本次发放积分数。
 */
public record VideoProgressVO(boolean finished, Integer points) {
}
