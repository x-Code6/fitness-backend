package com.yuedong.adminserver.controller;

import com.yuedong.adminserver.annotation.OperateLog;
import com.yuedong.adminserver.annotation.RequireRole;
import com.yuedong.adminserver.model.vo.MemberVO;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.Result;
import com.yuedong.point.api.client.PointClient;
import com.yuedong.point.api.dto.PointBriefDTO;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.MemberDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 会员管理（仅超管：禁用会员影响登录，属高危操作）。
 * 页面数据 = 用户基础信息（user 服务）+ 积分/打卡（point 服务）聚合。
 */
@Slf4j
@RestController
@RequestMapping("/admin/member")
@RequiredArgsConstructor
@RequireRole("SUPER")
public class MemberAdminController {

    private final UserClient userClient;
    private final PointClient pointClient;

    @GetMapping("/page")
    public Result<PageDTO<MemberVO>> page(@RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer size) {
        // ===== 第 1 步：用户基础信息分页 =====
        PageDTO<MemberDTO> data = userClient.memberPage(keyword, page, size).getData();
        if (data == null || data.getList().isEmpty()) {
            return Result.ok(PageDTO.of(List.of(), data == null ? 0 : data.getTotal()));
        }

        // ===== 第 2 步：积分/打卡数据聚合（point 服务批量查；失败降级为 0） =====
        Map<Long, PointBriefDTO> pointMap = fetchPointBriefs(data.getList());
        List<MemberVO> rows = data.getList().stream()
                .map(m -> {
                    PointBriefDTO p = pointMap.get(m.getId());
                    return MemberVO.builder()
                            .id(m.getId())
                            .nickname(m.getNickname())
                            .phone(m.getPhone())
                            .status(m.getStatus())
                            .createdAt(m.getCreatedAt() == null ? null : m.getCreatedAt().toString())
                            .points(p == null ? 0 : p.getBalance())
                            .totalCheckin(p == null ? 0 : p.getTotalCheckin())
                            .build();
                })
                .toList();
        return Result.ok(PageDTO.of(rows, data.getTotal()));
    }

    @OperateLog(module = "会员管理", operation = "禁用/启用会员")
    @PostMapping("/status")
    public Result<Void> status(@RequestParam Long id, @RequestParam Integer status) {
        return userClient.memberStatus(id, status);
    }

    /** 批量拉积分简况并按 userId 建映射；point 服务不可用时返回空映射（列表积分列显示 0） */
    private Map<Long, PointBriefDTO> fetchPointBriefs(List<MemberDTO> members) {
        try {
            String userIds = members.stream()
                    .map(m -> String.valueOf(m.getId()))
                    .collect(Collectors.joining(","));
            var briefs = pointClient.brief(userIds);
            if (briefs.getCode() == 0 && briefs.getData() != null) {
                return briefs.getData().stream()
                        .collect(Collectors.toMap(PointBriefDTO::getUserId, Function.identity()));
            }
        } catch (Exception e) {
            log.warn("积分简况聚合失败，降级显示 0", e);
        }
        return Map.of();
    }
}
