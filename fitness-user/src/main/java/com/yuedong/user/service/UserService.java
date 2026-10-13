package com.yuedong.user.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.user.api.dto.MemberDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import com.yuedong.user.model.dto.LoginDTO;
import com.yuedong.user.model.dto.RegisterDTO;
import com.yuedong.user.model.vo.LoginVO;
import com.yuedong.user.model.vo.ProfileVO;

import java.util.List;

/**
 * 用户业务接口（三层架构中的 Service 层：业务逻辑都写在这里，Controller 只做参数接收与编排）。
 */
public interface UserService {

    /**
     * 注册：手机号查重 → BCrypt 加密密码 → 落库。
     *
     * @param dto 注册参数（手机号/密码/可选昵称）
     */
    void register(RegisterDTO dto);

    /**
     * 账号密码登录：校验密码 → 签发 JWT。
     *
     * @param dto 登录参数（手机号/密码）
     * @return token + 昵称
     */
    LoginVO login(LoginDTO dto);

    /**
     * 查询当前登录用户的资料。
     *
     * @param userId 当前用户 id（来自网关透传的 X-User-Id）
     */
    ProfileVO getProfile(Long userId);

    /**
     * 修改昵称。
     *
     * @param userId   当前用户 id
     * @param nickname 新昵称
     * @return 更新后的资料
     */
    ProfileVO updateNickname(Long userId, String nickname);

    /**
     * 内部接口：按 id 批量查用户简况（排行榜/Feed 展示昵称用）。
     */
    List<UserBriefDTO> batchQuery(List<Long> ids);

    /**
     * 管理端：会员分页（昵称/手机号搜索），按 MemberDTO 输出（不含密码散列）。
     */
    PageDTO<MemberDTO> adminPage(String keyword, Integer page, Integer size);

    /**
     * 管理端：禁用/启用会员。
     *
     * @param status 0=正常 1=禁用
     */
    void adminSetStatus(Long id, Integer status);
}
