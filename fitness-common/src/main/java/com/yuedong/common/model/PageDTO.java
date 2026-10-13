package com.yuedong.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页数据传输对象（跨服务内部接口与对外接口共用的分页信封）。
 *
 * <p>所有 /inner/** 分页接口统一返回 {@code Result<PageDTO<T>>}，
 * 避免各服务各自用 Map.of("list", ..., "total", ...) 拼散装结构。</p>
 *
 * @param <T> 行数据类型（DTO，不直接暴露数据库实体）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageDTO<T> {

    /** 当前页数据 */
    private List<T> list;

    /** 总条数 */
    private long total;

    /** 从 MyBatis-Plus 的 Page 构造（把实体转 DTO 后调用） */
    public static <T> PageDTO<T> of(List<T> list, long total) {
        return new PageDTO<>(list, total);
    }
}
