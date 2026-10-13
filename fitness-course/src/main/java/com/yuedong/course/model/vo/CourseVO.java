package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 课程视图对象。
 *
 * <p>列表页与详情页共用：详情页多返回 description/suitable；
 * seckill 字段仅在该课程有"进行中"秒杀活动时非空（前端据此切换秒杀价/倒计时）。</p>
 */
@Data
@Builder
public class CourseVO {

    private Long id;

    private String name;

    /** 类型：团课/私教/瑜伽/骑行/操课（前端按类型渲染封面渐变色） */
    private String type;

    private String coach;

    private String coachTitle;

    private Integer duration;

    private Integer calories;

    private Integer capacity;

    private Integer booked;

    private BigDecimal price;

    /** 标签列表（DB 逗号串拆分而来） */
    private List<String> tags;

    private String storeName;

    /** 下一次开课时间文案，如"今天 19:00" */
    private String nextTime;

    private String description;

    private String suitable;

    /** 进行中的秒杀信息，无活动为 null */
    private SeckillInfo seckill;

    /**
     * 秒杀信息内嵌对象。
     */
    @Data
    @Builder
    public static class SeckillInfo {

        private Long activityId;

        /** 秒杀价 */
        private BigDecimal price;

        private Integer total;

        private Integer remain;

        /** 结束时间戳（毫秒），前端倒计时用 */
        private Long endTime;
    }
}
