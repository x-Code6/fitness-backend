package com.yuedong.point.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.point.api.dto.GiftDTO;
import com.yuedong.point.api.dto.GiftSaveDTO;
import com.yuedong.point.model.vo.GiftVO;

import java.util.List;

/**
 * 积分商城业务接口。
 */
public interface GiftService {

    /** 上架商品列表（前端积分商城页） */
    List<GiftVO> list();

    /**
     * 积分兑换：CAS 扣积分 + 原子扣库存（同事务，任一失败整体回滚）+ 流水 + 通知。
     */
    void exchange(Long userId, Long giftId);

    /** 管理端：商品分页 */
    PageDTO<GiftDTO> adminPage(Integer page, Integer size);

    /** 管理端：新增/编辑商品 */
    void adminSave(GiftSaveDTO dto);

    /** 管理端：补库存 */
    void addStock(Long giftId, Integer add);
}
