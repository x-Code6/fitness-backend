package com.yuedong.adminserver.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.adminserver.mapper.OperateLogMapper;
import com.yuedong.adminserver.model.entity.OperateLog;
import com.yuedong.adminserver.model.vo.OperateLogVO;
import com.yuedong.adminserver.service.LogService;
import com.yuedong.common.model.PageDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 操作日志查询实现。
 */
@Service
@RequiredArgsConstructor
public class LogServiceImpl implements LogService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OperateLogMapper operateLogMapper;

    @Override
    public PageDTO<OperateLogVO> page(Integer page, Integer size) {
        Page<OperateLog> result = operateLogMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 15 : size),
                new LambdaQueryWrapper<OperateLog>().orderByDesc(OperateLog::getId));
        List<OperateLogVO> rows = result.getRecords().stream()
                .map(l -> OperateLogVO.builder()
                        .adminName(l.getAdminName())
                        .module(l.getModule())
                        .operation(l.getOperation())
                        .params(l.getParams())
                        .durationMs(l.getDurationMs())
                        .success(l.getSuccess())
                        .time(l.getCreatedAt() == null ? null : l.getCreatedAt().format(TIME_FMT))
                        .build())
                .toList();
        return PageDTO.of(rows, result.getTotal());
    }
}
