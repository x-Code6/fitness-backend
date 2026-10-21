package com.yuedong.user.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会员详情 DTO（管理端会员分页行数据，不含密码散列等敏感字段）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberDTO {

    private Long id;

    private String nickname;

    private String phone;

    /** 0=正常 1=禁用 */
    private Integer status;

    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")

    private LocalDateTime createdAt;
}
