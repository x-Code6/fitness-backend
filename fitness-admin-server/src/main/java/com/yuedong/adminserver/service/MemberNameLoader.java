package com.yuedong.adminserver.service;

import com.yuedong.common.result.Result;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会员昵称批量补齐（BFF 侧唯一的"跨服务补名字"入口）。
 *
 * <p>★为什么单独一个组件而不是每个 controller 各写一份：
 * 订单、凭证、预约三个管理页都要"userId → 昵称"这一件事。
 * 抄三遍的代价不是行数，是<b>降级策略会分叉</b>——第一份写"失败返回空 Map"，
 * 第三份写成"失败抛异常"，于是同一个用户服务抖动，三个页面的表现各不相同，
 * 排查时会以为是三个不同的故障。</p>
 *
 * <p>★这里<b>不做缓存</b>：昵称会被用户改，管理端要看到的是当前值；
 * 而一页最多 20 个 id，一次 Feign 的代价完全可接受。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberNameLoader {

    private final UserClient userClient;

    /**
     * @param userIds 本页出现过的用户 id（可为 null、可含重复，内部去重）
     * @return id → 昵称；用户服务不可用时返回<b>空 Map</b>（列表昵称列显示空，不让整个页面 500）
     */
    public Map<Long, String> mapOf(Collection<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        List<Long> distinct = userIds.stream().filter(id -> id != null).distinct().toList();
        if (distinct.isEmpty()) {
            return names;
        }
        try {
            Result<List<UserBriefDTO>> result = userClient.batch(new BatchUserQueryDTO(distinct));
            if (result.getCode() == 0 && result.getData() != null) {
                for (UserBriefDTO user : result.getData()) {
                    names.put(user.getId(), user.getNickname());
                }
            }
        } catch (Exception e) {
            // ★只记日志不抛：昵称是装饰性信息，主数据（订单号、金额、时段）已经拿到了，
            // 不能因为补名字失败让管理页整页报错
            log.warn("会员昵称补齐失败，本页昵称显示为空: {}", e.getMessage());
        }
        return names;
    }
}
