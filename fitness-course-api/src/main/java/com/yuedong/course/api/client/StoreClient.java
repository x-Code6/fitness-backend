package com.yuedong.course.api.client;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.dto.StoreDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 门店内部契约（order / admin-server 经 Feign 调用）。
 *
 * <p>门店数据在 {@code yd_course.t_store}，但两个消费方都需要它：
 * order 算可约时段要营业时间、订单/预约详情要地址；admin-server 是门店管理页。</p>
 */
@FeignClient(name = "fitness-course", contextId = "storeClient")
public interface StoreClient {

    /**
     * 单店详情。
     *
     * @param storeId 门店 id
     * @return 门店信息；门店不存在时 {@code data=null}（★调用方要判空，
     *         不能假定门店一定在——管理员随时可能删店）
     */
    @GetMapping("/inner/store/detail")
    Result<StoreDTO> detail(@RequestParam("storeId") Long storeId);

    /** 全部门店（演示环境只有几家，一次取全比按 id 循环取可靠） */
    @GetMapping("/inner/store/list")
    Result<List<StoreDTO>> list();
}
