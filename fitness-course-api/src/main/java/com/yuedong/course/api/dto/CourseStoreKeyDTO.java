package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "课程 × 门店"的一对，批量取快照时当参数用。
 *
 * <p>★为什么要成对传而不是两个列表：购物车里"课 1 在 A 店、课 2 在 B 店"和
 * "课 1 在 B 店"是完全不同的三条信息（价格窗口、可售状态、门店名都按店定）。
 * 拆成两个列表传过去，服务端就得笛卡尔积再筛，语义就丢了。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseStoreKeyDTO {

    private Long courseId;

    private Long storeId;
}
