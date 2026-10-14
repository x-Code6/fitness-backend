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
     * <p>★语义以 {@code t_user.status} 与 User 实体为准：<b>1=正常，0=禁用</b>。
     * 这里的注释以前写反过（写成 0=正常 1=禁用），而 status 是 0/1 这种"看起来都合理"的
     * 数字编码，注释写反时调用方会照反的传，表现就是"点禁用变成启用"——
     * 所以数字状态码的注释必须和 DDL 的 COMMENT 一字不差。</p>
     *
     * @param status 1=正常 0=禁用；传 0 时实现里会同步吊销该用户的全部登录令牌
     */
    void adminSetStatus(Long id, Integer status);

    /**
     * 管理端看板：正常状态（status=1）的会员总数。
     * <p>★单独给一个 count 而不是让调用方用 {@code adminPage(size=1)} 读 total：
     * 那种写法要付一次 ORDER BY id DESC 的排序成本还要依赖分页信封的结构，
     * 语义上也不是"我要一页"。少一个数就加一个只读方法，比 misuse 现成接口便宜。</p>
     */
    long countEnabled();
}
