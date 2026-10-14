package com.yuedong.order.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 预约视图（"我的预约"列表、即将上课卡片、预约详情共用）。
 *
 * <p>★字段名对齐前端 Mock 的 {@code reservationVO}。时间一律给格式化字符串并且
 * <b>拆成 dateText/startText/endText/fullText 四个粒度</b>：列表只显示日期，
 * 卡片要"今天 18:00-20:00"，详情页要完整串。只给一个串的话前端必然要再拆一遍，
 * 而拆分逻辑在三个页面各写一份就是三个出错点。</p>
 */
@Data
@Builder
public class ReservationVO {

    /** 对外预约单号（取消要回传它） */
    private String reservationNo;

    private Long courseId;

    private String courseName;

    private Long storeId;

    private String storeName;

    /** 门店地址。★来自一次跨服务查询，取不到时给空串、不显示这一行（不影响其他信息） */
    private String address;

    private Long coachId;

    /** 教练名快照（改名/离职后仍显示当时的名字） */
    private String coachName;

    private Integer lessonHours;

    /** 上课日期 "2026-10-14" */
    private String dateText;

    /** 日期友好文案 "今天" / "明天" / "周六" */
    private String dateLabel;

    /** 星期文案 "周六" */
    private String weekText;

    /** 起始整点 "18:00" */
    private String startText;

    /** 结束整点 "20:00" */
    private String endText;

    /** 完整串 "2026-10-14 18:00-20:00" */
    private String fullText;

    /** 时段轴状态 */
    private String status;

    private String statusText;

    /** 到店核销码（列表也回显，用户截图就能去店里出示） */
    private String verifyCode;

    /** 是否已核销（到店登记过）。★不决定扣次，只是记录 */
    private Boolean verified;

    /**
     * 能不能取消 = {@code status=BOOKED 且 now < cancel_deadline}。
     * <p>★后端算：这一条判断依赖服务器时间与数据库 cancel_deadline 的比较，
     * 前端拿自己的时钟算会出现"按钮能点、提交报无法取消"。</p>
     */
    private Boolean canCancel;

    /** 取消截止文案 "2026-10-14 17:00 前可取消" */
    private String cancelDeadlineText;

    /** 所属凭证剩余次数摘要 "剩 1 次" / "不限次"（详情页要在预约信息旁边直接告诉用户） */
    private String entitlementRemainText;

    /** 来源订单号，详情页跳回订单用 */
    private String orderNo;

    /**
     * 可执行动作：{@code CANCEL / VERIFY_CODE / RATE / AGAIN}。
     * <p>★和订单一样，按钮由后端算，前端只渲染。</p>
     */
    private List<String> actions;
}
