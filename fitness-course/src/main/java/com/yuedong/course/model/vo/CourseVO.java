package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 课程视图对象（列表页与详情页共用，详情页字段更全）。
 *
 * <p>★v2 去掉的字段与原因（这些不是删功能，是它们已经不属于"课程"了）：</p>
 * <ul>
 *   <li>{@code capacity} / {@code booked}：座位数与已约人数。团课取消，
 *       容量判断变成"该教练该时段是否被占"，在 yd_order 的占用表里；</li>
 *   <li>{@code nextTime}："今天 19:00"这类文案。时刻不再属于课程，
 *       列表改显示"最近可约时间"，由时段接口算（待 order 域接入，当前为 null）；</li>
 *   <li>{@code coach} / {@code coachTitle}：教练变成实体，详情页给
 *       {@code coachCount}（几位教练可约），教练明细走
 *       {@code /api/course/coach/list}；</li>
 *   <li>{@code storeName}（单店）：一门课可在多店售卖，列表给 {@code storeCount}，
 *       详情给 {@code stores}。</li>
 * </ul>
 *
 * <p>★为什么 VO 里既有 {@code price}(BigDecimal) 又有 {@code priceText}(String)：
 * 前端多处要显示金额，如果每处都自己 {@code toFixed(2)} 加货币符号，
 * 三个页面就会有三套格式。金额与时间的格式化统一在后端做，
 * 原始数值同时保留，便于前端做比较或计算。</p>
 */
@Data
@Builder
public class CourseVO {

    private Long id;

    private String name;

    /** SALE 可购买可约课 / CONTENT 展示型课程。前端据此决定按钮是"立即购买"还是"开始跟练" */
    private String kind;

    /** 分类标签（燃脂/力量/拉伸/心肺/体态/康复），前端按类型渲染封面渐变色 */
    private String type;

    /** 一节课几小时。列表显示"1 小时/节"，时段页据此决定一次占几个格子 */
    private Integer lessonHours;

    /** TIMES 按次 / UNLIMITED 不限次；CONTENT 为 null */
    private String saleType;

    /** 买一单给几次（仅 TIMES） */
    private Integer timesPerBuy;

    /** 课程净时长（分钟），展示用 */
    private Integer duration;

    private Integer calories;

    /** 原价数值，供前端比较/计算 */
    private BigDecimal price;

    /** 原价展示串，如"¥68.00" */
    private String priceText;

    /** 标签列表（DB 逗号串拆分而来）。★拆分在后端做，别让前端处理逗号串 */
    private List<String> tags;

    /** 封面图相对路径；null 时前端用 coverColor 画渐变 */
    private String coverImage;

    /** 封面渐变色下标 0~5 */
    private String coverColor;

    /** 可售门店数（列表用） */
    private Integer storeCount;

    /** 可售门店明细（仅详情页填） */
    private List<StoreOption> stores;

    /** 该门店下可约教练数（仅详情页带 storeId 请求时填） */
    private Integer coachCount;

    /** 详情长文（仅详情页填） */
    private String description;

    /** 适合人群（仅详情页填） */
    private String suitable;

    /** 列表用一句话规则摘要，如"2 小时/节 · 一次 2 次 · 领后 60 天有效" */
    private String ruleSummary;

    /** 详情页整块规则条目（由规则说明策略生成，见 docs 4.3.5） */
    private List<String> ruleTexts;

    /** 进行中的秒杀信息，无活动为 null */
    private SeckillInfo seckill;

    /**
     * 门店下拉项（详情页"在哪家店买"）。
     */
    @Data
    @Builder
    public static class StoreOption {
        private Long storeId;
        private String storeName;
        /** 该店是否仍在可售窗口内（停售的显示为不可选，而不是隐藏，避免用户以为课程坏了） */
        private Boolean onSale;
    }

    /**
     * 秒杀信息内嵌对象。
     */
    @Data
    @Builder
    public static class SeckillInfo {
        private Long activityId;
        /** 秒杀价数值 */
        private BigDecimal price;
        /** 秒杀价展示串，如"¥19.90" */
        private String priceText;
        private Integer total;
        private Integer remain;
        /** 结束时间戳（毫秒），前端倒计时用 */
        private Long endTime;
    }
}
