package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 教练 DTO（跨服务契约）：order 服务算"可约时段"时要拿的<b>计算列</b>。
 *
 * <p>★为什么不复用课程模块里的 {@code CoachVO}：那是 C 端展示对象，
 * 刻意不带 {@code workDays}（星期已经参与后端计算，前端不需要再算）。
 * 而时段页必须按星期判断"这个教练今天上不上班"，所以跨服务契约要的是<b>计算所需的原始字段</b>，
 * 展示 VO 不能当契约用 —— 一当契约就会为了加字段把展示层搞脏。</p>
 *
 * <p>同样不给 {@code phone}：内部接口也不该把个人联系方式传来传去，
 * order 服务用不到它。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoachDTO {

    private Long coachId;

    /** 教练名。★预约单要把它存成快照，改名/离职后历史记录仍显示当时的名字 */
    private String name;

    /** 资质文案，时段页表头展示 */
    private String title;

    private String avatarColor;

    /** 隶属门店。order 侧要校验"教练就在凭证的那家店" */
    private Long storeId;

    /** 每日可约起始整点（0~23） */
    private Integer workStartHour;

    /** 每日可约结束整点（1~24） */
    private Integer workEndHour;

    /**
     * 可约星期，逗号分隔，1=周一 … 7=周日。
     * <p>★用字符串而不是 JSON 数组：与库里 {@code t_coach.work_days} 一致，
     * 跨服务不再做一层序列化转换；order 侧 split 一次即可。</p>
     */
    private String workDays;
}
