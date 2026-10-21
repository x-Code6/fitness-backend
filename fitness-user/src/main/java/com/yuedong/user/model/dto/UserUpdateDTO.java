package com.yuedong.user.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料入参（PUT /user/profile）。
 *
 * <p>字段全部可空，语义是"传了才改"：只传头像不会把昵称清空。
 * 头像存的是上传服务返回的访问路径（形如 {@code /uploads/avatar/xxx.png}），
 * 不是图片二进制。</p>
 */
@Data
public class UserUpdateDTO {

    @Size(max = 20, message = "昵称最长 20 个字符")
    private String nickname;

    /** 头像访问路径，长度上限与 t_user.avatar_image 对齐 */
    @Size(max = 255, message = "头像路径过长")
    private String avatarImage;
}
