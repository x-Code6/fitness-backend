package com.yuedong.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.util.JwtUtil;
import com.yuedong.user.api.dto.MemberDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import com.yuedong.user.model.dto.LoginDTO;
import com.yuedong.user.model.dto.RegisterDTO;
import com.yuedong.user.model.entity.User;
import com.yuedong.user.mapper.UserMapper;
import com.yuedong.user.service.UserService;
import com.yuedong.user.model.vo.LoginVO;
import com.yuedong.user.model.vo.ProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 用户业务实现。
 *
 * <p>@RequiredArgsConstructor：为所有 final 字段生成构造器，
 * 配合 Spring 的构造器注入——字段不可变、脱离容器也能 new 出来单测，
 * 这是官方推荐的注入方式（比 @Autowired 字段注入好）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    /** 登录令牌白名单：签发时写入、禁用/踢下线时删除，网关按它判 token 是否仍有效 */
    private final StringRedisTemplate redisTemplate;

    /** 头像配色数量（与前端 AVATAR_COLORS 渐变色盘数量一致） */
    private static final int AVATAR_COLOR_COUNT = 6;

    /** 新用户默认昵称前缀 */
    private static final String DEFAULT_NICKNAME = "悦动同学";

    // =====================================================================
    // 注册
    // =====================================================================
    @Override
    public void register(RegisterDTO dto) {
        // ===== 第 1 步：手机号查重 =====
        // 先查一次给用户友好提示；真正兜底靠数据库的 phone 唯一索引（见第 4 步），
        // 否则并发注册两个相同手机号会双双通过这里的检查。
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        if (count > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // ===== 第 2 步：密码 BCrypt 散列 =====
        // BCrypt 自带随机盐且校验时从散列里取盐，无需单独存盐字段；
        // 绝不能存明文/MD5（MD5 可被彩虹表秒破）。
        String passwordHash = passwordEncoder.encode(dto.getPassword());

        // ===== 第 3 步：组装实体并落库 =====
        User user = new User();
        user.setPhone(dto.getPhone());
        user.setPassword(passwordHash);
        // 昵称未填时给默认值 + 手机号后四位，如"悦动同学8819"
        user.setNickname(Objects.requireNonNullElseGet(dto.getNickname(),
                () -> DEFAULT_NICKNAME + dto.getPhone().substring(7)));
        user.setAvatarColor(String.valueOf(ThreadLocalRandom.current().nextInt(AVATAR_COLOR_COUNT)));
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // ===== 第 4 步：数据库唯一索引兜底（并发注册同号时走到这里） =====
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        log.info("用户注册成功: userId={}, phone={}", user.getId(), mask(user.getPhone()));
    }

    // =====================================================================
    // 登录
    // =====================================================================
    @Override
    public LoginVO login(LoginDTO dto) {
        // ===== 第 1 步：按手机号查用户 =====
        // LambdaQueryWrapper：用方法引用 User::getPhone 指定列，编译期检查字段名，
        // 避免"字符串列名写错、运行时才发现"的问题
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));

        // ===== 第 2 步：校验密码 =====
        // "用户不存在"和"密码错误"统一提示 PHONE_OR_PASSWORD_ERROR——
        // 不给攻击者探测"该手机号是否已注册"的机会（防撞库侦察）
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PHONE_OR_PASSWORD_ERROR);
        }

        // ===== 第 3 步：账号状态校验 =====
        if (!Objects.equals(user.getStatus(), 1)) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }

        // ===== 第 4 步：签发 JWT 并登记令牌白名单 =====
        // jti = 这一次登录的身份证。同一账号登录三次会得到三个 jti，
        // 于是"只踢掉某一个设备"和"禁用账号（全部吊销）"两种操作都能实现。
        String jti = UUID.randomUUID().toString().replace("-", "");
        String token = jwtUtil.generate(user.getId(), jti);
        registerToken(user.getId(), jti);

        // ===== 第 5 步：记录最后登录时间（管理端会员列表要展示） =====
        // 只更新这一列，用 updateById 部分更新；注意 MP 的 updateById 默认忽略 null 字段，
        // 所以这里 new 一个只带 id + 待改字段的 patch 对象是安全的，不会把别的列覆盖掉
        User patch = new User();
        patch.setId(user.getId());
        patch.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(patch);

        log.info("用户登录成功: userId={}, jti={}", user.getId(), jti);
        return new LoginVO(token, user.getNickname());
    }

    /**
     * 把 jti 登记进 Redis 白名单。
     *
     * <p>写两个 key 各有用途：{@code login:token:{jti}} 给网关做每请求的 O(1) 校验；
     * {@code login:user:{userId}} 这个 Set 给"吊销某用户全部登录态"用——没有它，
     * 禁用账号时不知道该删哪些 jti。</p>
     *
     * <p>TTL 与 token 有效期严格一致（都取 {@code jwtUtil.expireSeconds()}），
     * 所以不需要清理任务：token 自然过期时白名单条目也自然消失，两边不会漂移。</p>
     */
    private void registerToken(Long userId, String jti) {
        Duration ttl = Duration.ofSeconds(jwtUtil.expireSeconds());

        // SET login:token:{jti} = userId，EX 与 token 同长
        redisTemplate.opsForValue().set(RedisKeyConstants.loginToken(jti), String.valueOf(userId), ttl);

        // SADD login:user:{userId} jti —— 集合同样续期，
        // 但要注意：集合的 TTL 是"每次写入整体续到最新一次登录的过期点"，
        // 早期登录的 jti 会跟着集合活得更久一点。多留的条目只是白名单里晚点消失，
        // 不影响正确性（各 jti 自己的 token 已过期，网关验签就先失败了）
        redisTemplate.opsForSet().add(RedisKeyConstants.loginUser(userId), jti);
        redisTemplate.expire(RedisKeyConstants.loginUser(userId), ttl);
    }

    /**
     * 吊销某用户的全部登录态：把他所有 jti 从白名单删掉，下一个请求就 401。
     *
     * <p>这是"JWT 无法主动失效"的正解。相比把角色/状态写进 token 再靠 tokenVersion 比对，
     * 白名单方案顺带还给了多端登录控制与"改密强制下线"两个能力。</p>
     */
    private void revokeAllTokens(Long userId) {
        String userKey = RedisKeyConstants.loginUser(userId);
        Set<String> jtiSet = redisTemplate.opsForSet().members(userKey);
        if (jtiSet == null || jtiSet.isEmpty()) {
            return;
        }
        // 批量删 token 键：一次调用而不是循环 delete，省掉 N 次网络往返
        List<String> tokenKeys = jtiSet.stream().map(RedisKeyConstants::loginToken).toList();
        redisTemplate.delete(tokenKeys);
        redisTemplate.delete(userKey);
        log.info("已吊销用户全部登录态: userId={}, 令牌数={}", userId, tokenKeys.size());
    }

    // =====================================================================
    // 资料
    // =====================================================================
    @Override
    public ProfileVO getProfile(Long userId) {
        // ===== 第 1 步：查库 =====
        User user = userMapper.selectById(userId);
        if (user == null) {
            // token 有效但用户被删，按未找到处理
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // ===== 第 2 步：实体 → VO（密码永远不出服务层） =====
        return toProfileVO(user);
    }

    @Override
    public ProfileVO updateNickname(Long userId, String nickname) {
        // ===== 第 1 步：确认用户存在 =====
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // ===== 第 2 步：只更新昵称字段（updateById 按"非 null 字段"拼 SET 子句） =====
        User patch = new User();
        patch.setId(userId);
        patch.setNickname(nickname);
        patch.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(patch);

        // ===== 第 3 步：返回最新资料 =====
        user.setNickname(nickname);
        return toProfileVO(user);
    }

    // =====================================================================
    // 内部接口实现
    // =====================================================================
    @Override
    public List<UserBriefDTO> batchQuery(List<Long> ids) {
        // selectBatchIds 等价于 WHERE id IN (...)
        return userMapper.selectBatchIds(ids).stream()
                .map(u -> UserBriefDTO.builder()
                        .id(u.getId())
                        .nickname(u.getNickname())
                        .avatarColor(u.getAvatarColor())
                        .build())
                .toList();
    }

    @Override
    public PageDTO<MemberDTO> adminPage(String keyword, Integer page, Integer size) {
        Page<User> result = userMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<User>()
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(User::getNickname, keyword.trim())
                                        .or().like(User::getPhone, keyword.trim()))
                        .orderByDesc(User::getId));
        // 实体 → DTO：密码散列在转换时天然丢弃，绝不外泄
        List<MemberDTO> rows = result.getRecords().stream()
                .map(u -> MemberDTO.builder()
                        .id(u.getId())
                        .nickname(u.getNickname())
                        .phone(u.getPhone()) // 管理端运营场景需要完整手机号（与原行为一致）
                        .status(u.getStatus())
                        .createdAt(u.getCreatedAt())
                        .build())
                .toList();
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public void adminSetStatus(Long id, Integer status) {
        User patch = new User();
        patch.setId(id);
        patch.setStatus(status);
        patch.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(patch);

        // ===== 禁用要"立即生效"，光改 status 列不够 =====
        // t_user.status 只在登录时校验（登录时 UserServiceImpl 会拒绝 status≠1），
        // 已经发出去的 token 在过期前照样能用——最长 30 天的空窗。
        // 所以禁用时同步吊销他的所有登录令牌，下一个请求就进不来；
        // 恢复启用不用吊销任何东西，用户重新登录即可拿到新 token。
        if (Objects.equals(status, 0)) {
            revokeAllTokens(id);
        }
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 实体 → 资料 VO，手机号脱敏：138****8819 */
    private ProfileVO toProfileVO(User user) {
        return ProfileVO.builder()
                .id(user.getId())
                .phone(mask(user.getPhone()))
                .nickname(user.getNickname())
                .avatarColor(user.getAvatarColor())
                .createdAt(user.getCreatedAt())
                .build();
    }

    /** 手机号脱敏：保留前 3 后 4 */
    private String mask(String phone) {
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }
}
