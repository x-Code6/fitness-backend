package com.yuedong.user.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录结果：token + 昵称（前端登录后直接展示欢迎语，省一次资料请求）。
 */
@Data
@AllArgsConstructor
public class LoginVO {

    /** JWT，前端存 localStorage 并在后续请求头携带 Authorization: Bearer <token> */
    private String token;

    /** 昵称 */
    private String nickname;
}
