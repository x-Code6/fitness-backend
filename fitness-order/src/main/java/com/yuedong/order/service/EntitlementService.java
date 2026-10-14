package com.yuedong.order.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.order.api.dto.EntitlementAdminDTO;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.model.entity.Entitlement;
import com.yuedong.order.model.vo.EntitlementVO;

import java.util.List;
import java.util.Map;

/**
 * 凭证业务接口（权益轴）。
 *
 * <p>三件事：<b>出票</b>（支付成功时）、<b>查验</b>（预约前）、<b>结账</b>（到点扣次与过期）。
 * 扣次的动作在预约分支里触发（{@code EntitlementMapper#consumeOne}），
 * 本接口只负责把凭证状态推对，不关心是谁来调。</p>
 */
public interface EntitlementService {

    /**
     * 支付成功后出票：按订单份数生成 N 张<b>独立</b>凭证（D4：多份不合并）。
     *
     * <p>★为什么不是一张"总次数=N"的凭证：退款/过期/展示全都按单张走，
     * 合并成一张就得支持部分退款与部分过期，那是第三条状态机之外的第四种复杂度。</p>
     *
     * <p>有效期由课程服务那边该门店的 valid_type 算出（本方法内部一次只读 Feign，
     * 失败按"永久"兜底，理由见实现）。★调用方负责事务：出票必须与"订单置 PAID"同事务，
     * 否则会出现"付了钱没有凭证"或"没付钱却有凭证"。</p>
     *
     * @param booking 已流转成 PAID 的订单
     * @return 本次生成的凭证列表，调用方可直接用于通知文案
     */
    List<Entitlement> grantForPaidOrder(Booking booking);

    /**
     * 我的凭证列表。
     *
     * @param userId     只从 UserContext 取，绝不接受前端传参
     * @param courseId   非空时只看这门课（进入课程页查"我已购"）
     * @param usableOnly true 时只返回当前可预约的（预约页下拉），null/false 返回全部含历史
     */
    List<EntitlementVO> listMine(Long userId, Long courseId, Boolean usableOnly);

    /**
     * 取一张可用凭证并校验归属。
     * <p>★WHERE 必带 user_id：只按 id 查就成了"猜到凭证 id 就能蹭别人的课"。</p>
     */
    Entitlement loadUsable(Long userId, Long entitlementId);

    /**
     * 按订单号批量取凭证，返回"订单号 → 该单凭证列表"。
     * <p>★订单列表/详情用它一次查完，绝不在循环里查库（N 张单 = 1 次查询）。</p>
     */
    Map<String, List<Entitlement>> mapByOrderNos(Long userId, List<String> orderNos);

    /** 这一单的凭证是否都已结束（用完或过期）——评价门槛（已确认口径：上完才让评）。
     * <p>⚠️ 只有一种情况返回 true：该单至少有一张凭证，且没有任何一张还是 ACTIVE。</p>
     */
    boolean isOrderFinished(String orderNo);

    /**
     * 实体 → VO。<b>公开这一个转换是为了让订单详情复用同一份字段口径</b>
     * （剩余次数、有效期文案、canBook、subTitle 的算法只有一处）。
     * <p>★不公开的话，订单详情要么自己再拼一遍 VO（两处字段迟早分叉），
     * 要么把实体直接吐给前端（会漏出 status 之外的内部字段）。
     * 转换方法放服务里对外暴露，是这两个坏选择之外的第三条路。</p>
     */
    EntitlementVO toVO(Entitlement entitlement);

    /**
     * 过期扫描（每小时由定时任务调用）：ACTIVE 且已过 valid_until 的凭证置 EXPIRED，
     * 其中一次都没用过的把订单推到 REFUNDED。
     *
     * @return 本轮处理的凭证张数（任务日志用）
     */
    int expireOverdue();

    /**
     * 管理端：凭证分页。
     *
     * @param status  ACTIVE / USED_UP / EXPIRED；null 或 ALL = 全部
     * @param keyword 模糊匹配订单号与课程名（<b>不含会员昵称</b>：昵称在 yd_user，跨库 LIKE 不了）
     * @param userId  指定会员，可为 null
     */
    PageDTO<EntitlementAdminDTO> adminPage(
            String status, String keyword, Long userId, Integer page, Integer size);

    /**
     * 实体 → 管理端 DTO。<b>公开理由与 {@link #toVO} 同一条</b>：
     * 订单详情里那一串凭证要用同一份"剩余次数怎么算、长期有效怎么显示"的口径，
     * 私有的话就得在详情里再拼一遍，两处迟早分叉。
     */
    EntitlementAdminDTO toAdminDTO(Entitlement entitlement);

    /**
     * 给一批管理端凭证行补"已约几次"。
     *
     * <p>★单独一步而不是塞进 {@link #toAdminDTO}：那个方法一次只转一张，
     * 而预约数必须<b>整页一次 GROUP BY</b> 才便宜。
     * 凭证分页和订单详情两处都要这个数，所以规则放在这里而不是各写一遍。</p>
     */
    void fillReservationCounts(List<EntitlementAdminDTO> rows);
}
