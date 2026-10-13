package com.yuedong.adminserver.service;

import com.yuedong.adminserver.model.vo.OperateLogVO;
import com.yuedong.common.model.PageDTO;

/**
 * 操作日志查询业务接口（数据在本服务 yd_admin 库）。
 */
public interface LogService {

    /** 日志分页（倒序） */
    PageDTO<OperateLogVO> page(Integer page, Integer size);
}
