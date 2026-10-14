package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端券列表行（跨服务契约，★必须 @NoArgsConstructor + @AllArgsConstructor）。
 *
 * <p>时间这里保留 {@code LocalDateTime} 而不是格式化字符串：
 * 管理端表格要排序与比较，C 端才需要"几月几号"这种人话。
 * <b>同一个概念在两个端口用两种类型是可以的，硬统一反而别扭。</b></p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponAdminDTO {

    private Long id;

    private String title;

    /** SECKILL / NORMAL */
    private String couponType;

    private BigDecimal threshold;

    private BigDecimal discount;

    private Integer total;

    private Integer remain;

    private Integer limitPerUser;

    private LocalDateTime receiveStartTime;

    private LocalDateTime receiveEndTime;

    /** FOREVER / DAYS / RANGE */
    private String validType;

    private Integer validDays;

    private LocalDateTime useStartTime;

    private LocalDateTime useEndTime;

    /** 0 全场 / 1 限课程 / 2 限门店 */
    private Integer scope;

    /** 已配的课程范围（编辑表单回显用） */
    private List<Long> courseIds;

    /** 已配的门店范围 */
    private List<Long> storeIds;

    private String description;

    private Integer status;
}
