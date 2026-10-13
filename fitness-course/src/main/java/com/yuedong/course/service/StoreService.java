package com.yuedong.course.service;

import com.yuedong.course.api.dto.StoreDTO;
import com.yuedong.course.api.dto.StoreSaveDTO;
import com.yuedong.course.model.vo.StoreVO;

import java.util.List;

/**
 * 门店业务接口。
 */
public interface StoreService {

    /** 附近门店：Redis GEO 按距离升序，支持关键词过滤 */
    List<StoreVO> nearby(String keyword);

    // =====================================================================
    // 管理端（fitness-admin-server Feign 调用）
    // =====================================================================

    /** 管理端：门店全量列表 */
    List<StoreDTO> adminList();

    /** 管理端：新增/编辑门店（编辑后重建 GEO） */
    void adminSave(StoreSaveDTO dto);

    /** 管理端：删除门店（同步删除 GEO 坐标） */
    void adminDelete(Long id);
}
