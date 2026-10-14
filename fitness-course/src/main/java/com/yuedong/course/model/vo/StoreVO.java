package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 门店视图对象（附近门店列表 / 门店详情）。
 *
 * <p>★v2 两处字段变化，都是"数据不在这个库了"的结果：</p>
 * <ul>
 *   <li>{@code rating}（旧的写死列）→ {@code ratingText} + {@code ratingCount}。
 *       评分来自 yd_order 的评价表，需要 Feign 批量取回后填充；
 *       <b>取不到时留 null 而不是 0</b>，前端按"暂无评分"渲染——
 *       0 分会被用户理解成"口碑很差"，null 才是"还没人评"。</li>
 *   <li>{@code coaches}（旧的写死列）→ {@code coachCount}，由教练表实时数出来
 *       （教练已经是实体，再维护一个计数字段就是第二份真相）。</li>
 * </ul>
 *
 * <p>{@code openHours} 改为 {@code openHoursText}：明确它是给人看的文案。
 * 可约时段的计算用 Store 实体里的 {@code openStartHour/openEndHour/is24h}，
 * 那些列不在本 VO 里出现，避免前端拿文案去切字符串。</p>
 */
@Data
@Builder
public class StoreVO {

    private Long id;

    private String name;

    /** 距用户位置的距离（公里，Redis GEO 算出） */
    private Double distance;

    /** 距离展示串，如"1.8km"。前端自己拼单位会出现"1.800000003km"这种显示 */
    private String distanceText;

    private String address;

    /** 门店图片相对路径 */
    private String image;

    /** 评分展示串，如"4.8"；null = 暂无评分 */
    private String ratingText;

    /** 评价条数；null 与 0 含义相同但不填，避免和"评分未取到"混淆 */
    private Integer ratingCount;

    /** 特色标签列表（后端已按逗号拆好） */
    private List<String> tags;

    /** 营业时间文案，仅展示 */
    private String openHoursText;

    private String phone;

    /** 该店可约教练数（只数 status=1 的） */
    private Integer coachCount;

    /** 该店在售课程数 */
    private Integer courseCount;
}
