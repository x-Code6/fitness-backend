package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 门店 DTO（管理端门店列表 / 编辑回填）。
 *
 * <p>★为什么这里要 @NoArgsConstructor + @AllArgsConstructor：
 * 它是跨服务传输的对象，消费方（admin-server）用 Jackson 反序列化，
 * 只有 @Builder 而没有无参构造时，Jackson 无法实例化，
 * 表现是"接口 200 但 data 全是 null"或直接 500。
 * 凡是放进 {@code *-api} 模块的 DTO 都必须满足这个要求。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreDTO {

    private Long id;

    private String name;

    /** 经度（同时用于 Redis GEO） */
    private BigDecimal lng;

    /** 纬度 */
    private BigDecimal lat;

    private String address;

    private String intro;

    /** 特色标签，逗号分隔（原样给管理端，编辑框也是字符串） */
    private String tags;

    /** 门店图片相对路径 */
    private String image;

    /** 营业开始整点（0~23）。★管理端要看到计算列，才能改得对 */
    private Integer openStartHour;

    /** 营业结束整点（1~24） */
    private Integer openEndHour;

    /** 1 全天营业 */
    private Integer is24h;

    /** 营业时间展示文案 */
    private String openHoursText;

    private String phone;

    /** 1 营业中 / 0 已关闭 */
    private Integer status;
}
