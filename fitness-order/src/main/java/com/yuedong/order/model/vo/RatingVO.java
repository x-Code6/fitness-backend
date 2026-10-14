package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 评价视图（课程评价列表 / 我的评价共用，★字段名对齐前端 Mock 的 {@code ratingVO}）。
 *
 * <p>{@code agoText}（"3 天前"）由后端算：这个"多久之前"看似前端一行代码，
 * 但服务器与手机的时钟/时区不一致时会出现"0 小时前""-3 小时前"这种脏文案。
 * <b>凡是要和"现在"比较出来的展示值，都在服务端算一次。</b></p>
 */
@Data
@Builder
public class RatingVO {

    /** 评价 id。★删除要回传它（不是 orderNo：orderNo 对外是订单，ratingId 才是这条评价） */
    private Long ratingId;

    private String orderNo;

    private Long courseId;

    /** 评价人。★公开列表要给昵称，"我的评价"里也用同一个字段，前端一套渲染 */
    private Long userId;

    private String userName;

    /** 头像配色下标（没有头像时画纯色圆） */
    private String avatarColor;

    private Integer score;

    private String comment;

    private Long coachId;

    private String coachName;

    /** "2026-10-12 20:15" */
    private String createdText;

    /** "3 天前" */
    private String agoText;

    /** 是不是当前用户发的（决定右上角要不要显示"删除"） */
    private Boolean mine;
}
