package com.yuedong.user.api.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量查询用户简况的请求体：{ "ids": [1, 2, 3] }。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchUserQueryDTO {

    @NotEmpty(message = "用户 id 列表不能为空")
    private List<Long> ids;
}
