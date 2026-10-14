package com.yuedong.course.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 领券中心的一张飞券（★字段名对齐前端 Mock 的 {@code couponVO}）。
 *
 * <p>★金额与状态一律"数值 + 文案"成对给：{@code threshold}(数值) +
 * {@code thresholdText}("满 200 可用")。只给数值的话，"无门槛"这种表达
 * 要在三个页面各写一遍三元表达式，写漏一处就显示"满 0 可用"。</p>
 *
 * <p>{@code tone} 一类的颜色不在这里给：颜色属视觉层，前端已有映射表。</p>
 */
@Data
@Builder
public class CouponVO {

    /** 券模板 id。★领要回传它（区别于"我的券"里的 recordId） */
    private Long couponId;

    private String title;

    /** SECKILL / NORMAL。前端据此切卡片样式（两类券长得就不一样） */
    private String couponType;

    private String couponTypeText;

    private BigDecimal threshold;

    /** "满 200 可用" / "无门槛" */
    private String thresholdText;

    private BigDecimal discount;

    /** "¥50" */
    private String discountText;

    /** 发放总量；普通券为 null（不限） */
    private Integer total;

    /** 当前剩余可领；普通券为 null（不限） */
    private Integer remain;

    /** 每人限领张数；★0 = 不限（不是 null，前端据此判断"0 就是不限"而不是"没配"） */
    private Integer limitPerUser;

    /** 我已经领了几张（配合 limitPerUser 显示"已领 1/2"） */
    private Integer received;

    /** 领取截止时间文案 */
    private String receiveEndText;

    /** 可用范围：0 全场 / 1 限课程 / 2 限门店 */
    private Integer scope;

    private String scopeText;

    /** 补充说明（管理员填的那句，规则明细在 ruleTexts 里） */
    private String description;

    /** 整块规则条目，由规则策略按券类型生成（D21） */
    private List<String> ruleTexts;

    /**
     * 现在能不能领 = 上架 + 在领取窗口内 + 还有库存（或不限）+ 没超个人限领。
     * <p>★四个条件后端算：前端自己拼会出现"按钮能点、提交报已领完"。</p>
     */
    private Boolean canReceive;

    /** 状态：1 上架 / 0 下架 / 2 作废。前端用它区分"已领完"与"已下架"两种灰态 */
    private Integer status;
}
