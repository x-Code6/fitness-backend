package com.yuedong.order.model.vo.slot;

import lombok.Builder;
import lombok.Data;

/**
 * 一个整点格子。
 *
 * <p>★四个分组字段（{@code groupIndex/groupSize/groupStart/groupBookable}）
 * 是 D3"一节课不可拆分"落到前端的关键，也是<b>最容易被前端算错的地方</b>：
 * lessonHours=2 时"18:00 空、19:00 被占"，18:00 那格<b>不能选</b>（放不下两小时）。
 * 如果让前端自己划组，边界上一定会出 bug —— 所以后端把组算好，前端只渲染。</p>
 *
 * <p>命名以后端 Mock 契约为准（{@code fitness-web} 已按这些字段名写完页面）。</p>
 */
@Data
@Builder
public class SlotItemVO {

    /** 整点起点 "18:00"。★只给起点，终点由 lessonHours 推 */
    private String startText;

    /** 下一个整点 "19:00"（格子自身的右边界，不是整节课的结束时间） */
    private String endText;

    /**
     * 这一格<b>现在能不能点</b>。
     * <p>★true 的条件是三件事同时成立：是组首格 + 整组都空闲 + 我有可用凭证。
     * 非组首的格子永远是 false（它只是被合并渲染进上一格的按钮里），
     * 前端不用自己判断，也就不会判错。</p>
     */
    private Boolean available;

    /** 不可约原因，见 {@link com.yuedong.order.constant.SlotReason}；可约时为 null */
    private String reason;

    /** 不可约原因的文案（前端直接显示，不用再维护一份 reason→文案 字典） */
    private String reasonText;

    /** 属于第几组（0 起） */
    private Integer groupIndex;

    /** 一组几格 = 课程的 lessonHours */
    private Integer groupSize;

    /** 是否组内第一格（只有它会被渲染成可点的按钮） */
    private Boolean groupStart;

    /** 整组是否都空闲（★组内每一格拿到的值相同，前端可据此给整组加灰） */
    private Boolean groupBookable;
}
