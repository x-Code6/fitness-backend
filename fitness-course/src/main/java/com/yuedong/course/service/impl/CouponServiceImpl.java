package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.mapper.CouponCourseMapper;
import com.yuedong.course.mapper.CouponMapper;
import com.yuedong.course.mapper.CouponStoreMapper;
import com.yuedong.course.mapper.UserCouponMapper;
import com.yuedong.course.model.entity.Coupon;
import com.yuedong.course.model.entity.CouponCourse;
import com.yuedong.course.model.entity.CouponStore;
import com.yuedong.course.model.entity.UserCoupon;
import com.yuedong.course.model.vo.CouponVO;
import com.yuedong.course.model.vo.MyCouponVO;
import com.yuedong.course.rule.RuleContext;
import com.yuedong.course.rule.RuleDescribeFactory;
import com.yuedong.course.rule.RuleTextConstants;
import com.yuedong.course.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 优惠券实现（v2 五项扩展全部落在这里）。
 *
 * <p>★<b>两类券两条领取路径</b>，这是本片最重要的取舍：
 * 秒杀券限量、高并发抢，必须用 Lua 把"判限领 + 扣库存 + 计数"三步合成一个原子操作；
 * 普通券不限量、低频领，一句 {@code SELECT COUNT(*)} 判限领后直接 INSERT 就够。
 * 给不限量券也套一遍 Lua，换来的是两套需要各自维护正确性的代码——
 * 这是"用高级方案套不需要场景"的典型，本项目按三档标准（必须防/值得防/不防）明确不这么写。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private static final String LUA_PATH = "lua/coupon_receive.lua";
    private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Lua 返回码：0 成功 */
    private static final long LUA_OK = 0L;
    /** Lua 返回码：1 库存已抢完 */
    private static final long LUA_SOLD_OUT = 1L;
    /** Lua 返回码：2 超过每人限领 */
    private static final long LUA_OVER_LIMIT = 2L;

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponCourseMapper couponCourseMapper;
    private final CouponStoreMapper couponStoreMapper;
    private final StringRedisTemplate redis;
    private final RuleDescribeFactory ruleDescribeFactory;

    /** 领券 Lua（启动时加载一次；脚本内容见 resources/lua/coupon_receive.lua） */
    private final DefaultRedisScript<Long> receiveScript = buildScript();

    private DefaultRedisScript<Long> buildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(LUA_PATH));
        script.setResultType(Long.class);
        return script;
    }

    // =====================================================================
    // 领券中心 / 我的券
    // =====================================================================
    @Override
    public List<CouponVO> listCenter(Long userId) {
        LocalDateTime now = LocalDateTime.now();

        // 查正在发放的券：上架 + 已到领取开始 + 未过领取截止
        // ★用 receive_* 两列而不是旧的 start/end：这一层只管"能不能领"，
        //   "领到以后能不能用"是第二层时间，混在一列上就配不出"领后 7 天有效"
        List<Coupon> coupons = couponMapper.selectList(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, 1)
                .le(Coupon::getReceiveStartTime, now)
                .ge(Coupon::getReceiveEndTime, now)
                .orderByDesc(Coupon::getId));
        if (coupons.isEmpty()) {
            return new ArrayList<>();
        }

        // ★我已领的张数一次查完再内存分组，不在循环里按券 COUNT（N 张券 = 1 次查询）
        Map<Long, Integer> receivedCount = new HashMap<>();
        if (userId != null) {
            List<UserCoupon> mine = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                    .eq(UserCoupon::getUserId, userId));
            for (UserCoupon record : mine) {
                // 按"领过几张"计，<b>含已使用的</b>：用完还能再领就不是限领了
                receivedCount.merge(record.getCouponId(), 1, Integer::sum);
            }
        }

        List<CouponVO> result = new ArrayList<>(coupons.size());
        for (Coupon coupon : coupons) {
            result.add(toVO(coupon, receivedCount.getOrDefault(coupon.getId(), 0)));
        }
        return result;
    }

    @Override
    public List<MyCouponVO> myCoupons(Long userId) {
        List<UserCoupon> records = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId));
        if (records.isEmpty()) {
            return new ArrayList<>();
        }

        // 一次 IN 批量取模板，★不在循环里逐条 selectById
        List<Long> couponIds = new ArrayList<>();
        for (UserCoupon record : records) {
            if (!couponIds.contains(record.getCouponId())) {
                couponIds.add(record.getCouponId());
            }
        }
        Map<Long, Coupon> couponMap = new HashMap<>();
        for (Coupon coupon : couponMapper.selectBatchIds(couponIds)) {
            couponMap.put(coupon.getId(), coupon);
        }

        LocalDateTime now = LocalDateTime.now();
        List<MyCouponVO> result = new ArrayList<>(records.size());
        for (UserCoupon record : records) {
            Coupon coupon = couponMap.get(record.getCouponId());
            if (coupon == null) {
                // 模板被物理删了（正常操作是下架不是删），这张券没名字没金额，给出去只会让页面出错
                continue;
            }
            LocalDateTime validUntil = calcValidUntil(coupon, record.getReceivedAt(), now);
            boolean usable = "UNUSED".equals(record.getStatus())
                    && coupon.getStatus() == 1
                    && (validUntil == null || validUntil.isAfter(now));

            result.add(MyCouponVO.builder()
                    .recordId(record.getId())
                    .couponId(coupon.getId())
                    .title(coupon.getTitle())
                    .couponType(coupon.getCouponType())
                    .typeText("SECKILL".equals(coupon.getCouponType()) ? "秒杀专享" : "普通券")
                    .threshold(coupon.getThreshold())
                    .discount(coupon.getDiscount())
                    .status(record.getStatus())
                    .statusText(statusText(record.getStatus()))
                    .receivedText(record.getReceivedAt() == null ? "" : record.getReceivedAt().format(DAY_TIME))
                    .validUntil(validUntil == null ? null : validUntil.format(DB_TIME))
                    .validText(validUntil == null ? "长期有效" : validUntil.format(DAY_TIME) + " 前可用")
                    .usedOrderNo(record.getOrderNo())
                    .usable(usable)
                    .build());
        }
        return result;
    }

    @Override
    public List<CouponUsableDTO> usableList(Long userId, Long courseId, Long storeId, BigDecimal amount) {

        // 查：只取还没用掉的持券记录。已 USED / 已过期的不进这个列表，
        // 因为选券页不需要"一张用过的券"出现在候选里，那是「我的券」页的事
        List<UserCoupon> records = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, "UNUSED")
                .orderByDesc(UserCoupon::getId));

        List<CouponUsableDTO> options = new ArrayList<>(records.size());
        for (UserCoupon record : records) {
            // ★逐张复用 usableInfo：门槛、使用时间窗口、课程白名单、门店白名单
            //   全部走同一份判断，这里不重复写任何规则，也不做"批量取模板再自己算"的优化
            //   ——那种优化就是把规则复制第二遍的开始。
            //   注意这是 N 次 selectById（券 + 模板各一次），但 N 是"一个人手里的券"，
            //   量级是个位数到十几，不值得为它写 JOIN。
            options.add(usableInfo(record.getId(), userId, amount, courseId, storeId));
        }

        // 可用的排前面，同为可用的按抵扣额从大到小：前端直接按顺序渲染就是"推荐的第一张"
        options.sort(Comparator
                .comparing((CouponUsableDTO option) -> option.isUsable() ? 0 : 1)
                .thenComparing(option -> option.getDiscount() == null
                        ? BigDecimal.ZERO : option.getDiscount(), Comparator.reverseOrder()));
        return options;
    }

    // =====================================================================
    // 领券
    // =====================================================================
    @Override
    public void receive(Long userId, Long couponId) {
        // ===== 第 1 步：券与领取窗口 =====
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || coupon.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getReceiveStartTime() == null || coupon.getReceiveEndTime() == null) {
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, "这张券没有配置领取时间");
        }
        if (now.isBefore(coupon.getReceiveStartTime()) || now.isAfter(coupon.getReceiveEndTime())) {
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, "不在领取时间内");
        }

        int limit = coupon.getLimitPerUser() == null ? 0 : coupon.getLimitPerUser();
        if ("SECKILL".equals(coupon.getCouponType())) {
            receiveSeckill(coupon, userId, limit, now);
        } else {
            receiveNormal(coupon, userId, limit);
        }
    }

    /** 秒杀券：Redis Lua 原子判限领 + 扣库存，成功后才写 DB 记录 */
    private void receiveSeckill(Coupon coupon, Long userId, int limit, LocalDateTime now) {
        if (coupon.getRemain() == null) {
            // ★秒杀券必须有库存数：没有就是管理员配漏了，
            // 这时候放行等于把"不限量"塞进限量券，账本立刻对不上
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, "该券未配置库存，暂不可领");
        }

        // TTL 到领取截止为止；已经临近截止的至少留 60 秒，避免 EXPIRE 0 立刻失效
        long ttl = Math.max(60, Duration.between(now, coupon.getReceiveEndTime()).toSeconds());
        Long code = redis.execute(receiveScript,
                List.of(RedisKeyConstants.couponStock(coupon.getId()), RedisKeyConstants.couponGot(coupon.getId())),
                String.valueOf(userId), String.valueOf(ttl), String.valueOf(limit));

        if (code != null && code == LUA_SOLD_OUT) {
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }
        if (code != null && code == LUA_OVER_LIMIT) {
            throw new BusinessException(ErrorCode.COUPON_DUPLICATE, "每人限领 " + limit + " 张");
        }
        if (code == null || code != LUA_OK) {
            // Redis 没给明确成功码就当下失败处理：宁可少发一张，不能超发
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }

        // ===== Redis 已扣，DB 写失败要回补（★回补的是计数和库存两处）=====
        try {
            insertRecord(userId, coupon.getId());
        } catch (Exception e) {
            restoreRedis(coupon.getId(), userId);
            log.error("领券写库失败，已回补 Redis: userId={}, couponId={}", userId, coupon.getId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "领取失败，请重试");
        }

        // DB 侧账本扣减。remain>0 条件保证不扣成负数；返回 0 说明 Redis 与 DB 已经不一致
        if (couponMapper.deductRemain(coupon.getId()) == 0) {
            restoreRedis(coupon.getId(), userId);
            log.error("券 DB 库存已空但 Redis 还有，按售罄处理: couponId={}", coupon.getId());
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }
        log.info("秒杀券领取成功: userId={}, couponId={}", userId, coupon.getId());
    }

    /** 普通券：不限量、低频，普通写法即可（三档标准里的"不防"） */
    private void receiveNormal(Coupon coupon, Long userId, int limit) {
        if (limit > 0) {
            // 查"这个人这张券领过几张"。★命中 idx_user_coupon(user_id, coupon_id, status) 的最左前缀
            Long got = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                    .eq(UserCoupon::getUserId, userId)
                    .eq(UserCoupon::getCouponId, coupon.getId()));
            if (got != null && got >= limit) {
                throw new BusinessException(ErrorCode.COUPON_DUPLICATE, "每人限领 " + limit + " 张");
            }
        }
        insertRecord(userId, coupon.getId());
        log.info("普通券领取成功: userId={}, couponId={}", userId, coupon.getId());
    }

    private void insertRecord(Long userId, Long couponId) {
        UserCoupon record = new UserCoupon();
        record.setUserId(userId);
        record.setCouponId(couponId);
        record.setStatus("UNUSED");
        record.setReceivedAt(LocalDateTime.now());
        userCouponMapper.insert(record);
    }

    /**
     * 回补 Redis：库存 +1、个人已领张数 -1。
     * <p>★两处都要回补。只回补库存不回补计数，用户会因为一次失败被记成"多领了一张"，
     * 之后再也不能领，而且完全看不出来。</p>
     */
    private void restoreRedis(Long couponId, Long userId) {
        redis.opsForValue().increment(RedisKeyConstants.couponStock(couponId));
        redis.opsForHash().increment(RedisKeyConstants.couponGot(couponId), String.valueOf(userId), -1);
    }

    // =====================================================================
    // 内部接口：用券校验 / 核销 / 退券
    // =====================================================================
    @Override
    public CouponUsableDTO usableInfo(Long recordId, Long userId, BigDecimal amount,
                                      Long courseId, Long storeId) {
        UserCoupon record = userCouponMapper.selectById(recordId);
        if (record == null || !Objects.equals(record.getUserId(), userId)) {
            return notUsable(recordId, null, "优惠券不存在");
        }
        Coupon coupon = couponMapper.selectById(record.getCouponId());
        if (coupon == null || coupon.getStatus() != 1) {
            return notUsable(recordId, record.getCouponId(), "优惠券已下架");
        }
        if (!"UNUSED".equals(record.getStatus())) {
            return notUsable(recordId, coupon.getId(), "优惠券已使用");
        }

        LocalDateTime now = LocalDateTime.now();
        // ===== 第二层时间：领到以后什么时候能用 =====
        if ("RANGE".equals(coupon.getValidType())) {
            if (coupon.getUseStartTime() != null && now.isBefore(coupon.getUseStartTime())) {
                return notUsable(recordId, coupon.getId(),
                        "这张券 " + coupon.getUseStartTime().format(DB_TIME) + " 之后才能用");
            }
            if (coupon.getUseEndTime() != null && now.isAfter(coupon.getUseEndTime())) {
                return notUsable(recordId, coupon.getId(), "优惠券已过期");
            }
        } else {
            LocalDateTime validUntil = calcValidUntil(coupon, record.getReceivedAt(), now);
            if (validUntil != null && now.isAfter(validUntil)) {
                return notUsable(recordId, coupon.getId(), "优惠券已过期");
            }
        }

        // ===== 门槛 =====
        if (amount == null || amount.compareTo(coupon.getThreshold()) < 0) {
            return notUsable(recordId, coupon.getId(),
                    "未达到使用门槛（满 " + plain(coupon.getThreshold()) + " 元可用）");
        }

        // ===== 可用范围。★scope 是显式字段，所以"没配范围"和"配漏了"能分清 =====
        if (coupon.getScope() != null && coupon.getScope() == 1) {
            if (courseId == null) {
                return notUsable(recordId, coupon.getId(), "这张券仅限指定课程使用");
            }
            Long hit = couponCourseMapper.selectCount(new LambdaQueryWrapper<CouponCourse>()
                    .eq(CouponCourse::getCouponId, coupon.getId())
                    .eq(CouponCourse::getCourseId, courseId));
            if (hit == null || hit == 0) {
                return notUsable(recordId, coupon.getId(), "这张券不能用于所选课程");
            }
        }
        if (coupon.getScope() != null && coupon.getScope() == 2) {
            if (storeId == null) {
                return notUsable(recordId, coupon.getId(), "这张券仅限指定门店使用");
            }
            Long hit = couponStoreMapper.selectCount(new LambdaQueryWrapper<CouponStore>()
                    .eq(CouponStore::getCouponId, coupon.getId())
                    .eq(CouponStore::getStoreId, storeId));
            if (hit == null || hit == 0) {
                return notUsable(recordId, coupon.getId(), "这张券不能用于所选门店");
            }
        }

        return CouponUsableDTO.builder()
                .recordId(recordId)
                .couponId(coupon.getId())
                .title(coupon.getTitle())
                .couponType(coupon.getCouponType())
                .threshold(coupon.getThreshold())
                .discount(coupon.getDiscount())
                .usable(true)
                .build();
    }

    @Override
    public boolean use(Long recordId, Long userId, String orderNo) {
        // CAS：仅 UNUSED 可核销并绑定订单号；订单取消/超时后由 refund 退回
        return userCouponMapper.use(recordId, userId, orderNo) > 0;
    }

    @Override
    public boolean refund(Long recordId) {
        return userCouponMapper.refund(recordId) > 0;
    }

    // =====================================================================
    // 管理端
    // =====================================================================
    @Override
    public PageDTO<CouponAdminDTO> adminPage(Integer page, Integer size) {
        Page<Coupon> result = couponMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 10 : size),
                new LambdaQueryWrapper<Coupon>().orderByDesc(Coupon::getId));
        List<Coupon> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageDTO.of(new ArrayList<>(), result.getTotal());
        }

        // ★两张范围表各一次 IN 批量取，回内存分组。逐条券查两次会变成 2N 次查询
        List<Long> couponIds = new ArrayList<>();
        for (Coupon coupon : rows) {
            couponIds.add(coupon.getId());
        }
        Map<Long, List<Long>> courseMap = new HashMap<>();
        for (CouponCourse row : couponCourseMapper.selectList(new LambdaQueryWrapper<CouponCourse>()
                .in(CouponCourse::getCouponId, couponIds))) {
            courseMap.computeIfAbsent(row.getCouponId(), key -> new ArrayList<>()).add(row.getCourseId());
        }
        Map<Long, List<Long>> storeMap = new HashMap<>();
        for (CouponStore row : couponStoreMapper.selectList(new LambdaQueryWrapper<CouponStore>()
                .in(CouponStore::getCouponId, couponIds))) {
            storeMap.computeIfAbsent(row.getCouponId(), key -> new ArrayList<>()).add(row.getStoreId());
        }

        List<CouponAdminDTO> list = new ArrayList<>(rows.size());
        for (Coupon coupon : rows) {
            list.add(CouponAdminDTO.builder()
                    .id(coupon.getId())
                    .title(coupon.getTitle())
                    .couponType(coupon.getCouponType())
                    .threshold(coupon.getThreshold())
                    .discount(coupon.getDiscount())
                    .total(coupon.getTotal())
                    .remain(coupon.getRemain())
                    .limitPerUser(coupon.getLimitPerUser())
                    .receiveStartTime(coupon.getReceiveStartTime())
                    .receiveEndTime(coupon.getReceiveEndTime())
                    .validType(coupon.getValidType())
                    .validDays(coupon.getValidDays())
                    .useStartTime(coupon.getUseStartTime())
                    .useEndTime(coupon.getUseEndTime())
                    .scope(coupon.getScope())
                    .courseIds(courseMap.getOrDefault(coupon.getId(), new ArrayList<>()))
                    .storeIds(storeMap.getOrDefault(coupon.getId(), new ArrayList<>()))
                    .description(coupon.getDescription())
                    .status(coupon.getStatus())
                    .build());
        }
        return PageDTO.of(list, result.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adminSave(CouponSaveDTO dto) {
        // ===== 第 1 步：跨字段校验。注解表达不了"哪种类型要读哪组列"，只能写在代码里 =====
        if (dto.getDiscount().compareTo(dto.getThreshold()) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "抵扣金额不能高于使用门槛");
        }
        LocalDateTime receiveStart = parseTime(dto.getReceiveStartTime(), "领取开始时间");
        LocalDateTime receiveEnd = parseTime(dto.getReceiveEndTime(), "领取截止时间");
        if (!receiveEnd.isAfter(receiveStart)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "领取截止必须晚于开始");
        }

        boolean seckill = "SECKILL".equals(dto.getCouponType());
        if (seckill) {
            // 秒杀券必须有总量与剩余，且限领张数必须 ≥1（要么不限=0，要么就认真写一个数）
            if (dto.getTotal() == null || dto.getTotal() < 1) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "秒杀券必须设置发放总量");
            }
            if (dto.getLimitPerUser() != null && dto.getLimitPerUser() < 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "每人限领不能为负数（0 表示不限）");
            }
        }
        LocalDateTime useStart = null;
        LocalDateTime useEnd = null;
        if ("DAYS".equals(dto.getValidType())) {
            if (dto.getValidDays() == null || dto.getValidDays() < 1) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "有效期选「领后 N 天」时必须填天数");
            }
        } else if ("RANGE".equals(dto.getValidType())) {
            useStart = parseTime(dto.getUseStartTime(), "可用开始时间");
            useEnd = parseTime(dto.getUseEndTime(), "可用截止时间");
            if (!useEnd.isAfter(useStart)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "可用截止必须晚于可用开始");
            }
        } else if (!"FOREVER".equals(dto.getValidType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "有效期类型只能是 FOREVER / DAYS / RANGE");
        }

        int scope = dto.getScope() == null ? 0 : dto.getScope();
        // ★"选了限课程却不给课程"要报错：这不是通用券，是配漏了。
        // 放过去会得到一张谁都不可用的券，用户领了才发现——最难解释的一类客诉
        if (scope == 1 && (dto.getCourseIds() == null || dto.getCourseIds().isEmpty())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "选了「仅限指定课程」必须至少勾选一门课");
        }
        if (scope == 2 && (dto.getStoreIds() == null || dto.getStoreIds().isEmpty())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "选了「仅限指定门店」必须至少勾选一家店");
        }

        // ===== 第 2 步：写模板 =====
        Coupon coupon;
        // 编辑时要先记下旧的总量/剩余，算出"已经发出去几张"——一旦被下面的 set 覆盖就再也拿不到了
        Integer oldTotal = null;
        Integer oldRemain = null;
        if (dto.getId() != null) {
            coupon = couponMapper.selectById(dto.getId());
            if (coupon == null) {
                throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
            }
            oldTotal = coupon.getTotal();
            oldRemain = coupon.getRemain();
        } else {
            coupon = new Coupon();
        }
        coupon.setTitle(dto.getTitle());
        coupon.setCouponType(seckill ? "SECKILL" : "NORMAL");
        coupon.setThreshold(dto.getThreshold());
        coupon.setDiscount(dto.getDiscount());
        coupon.setReceiveStartTime(receiveStart);
        coupon.setReceiveEndTime(receiveEnd);
        coupon.setValidType(dto.getValidType());
        coupon.setValidDays("DAYS".equals(dto.getValidType()) ? dto.getValidDays() : null);
        coupon.setUseStartTime(useStart);
        coupon.setUseEndTime(useEnd);
        coupon.setScope(scope);
        coupon.setDescription(dto.getDescription());
        // 普通券不限量：total/remain 一律置 null，避免表单残留的旧值被当成限量券
        coupon.setTotal(seckill ? dto.getTotal() : null);
        coupon.setLimitPerUser(dto.getLimitPerUser() == null ? 0 : dto.getLimitPerUser());

        if (dto.getId() != null) {
            // ★remain 不能直接盖成新的 total：那等于把已经发出去的券凭空还回库存，
            // 而那些券还在用户卡包里，账本立刻对不上。正确算法是"新总量 - 已发张数"
            int issued = oldTotal == null || oldRemain == null
                    ? 0 : Math.max(oldTotal - oldRemain, 0);
            coupon.setRemain(seckill ? Math.max((dto.getTotal() == null ? 0 : dto.getTotal()) - issued, 0) : null);
            coupon.setStatus(dto.getStatus() == null ? coupon.getStatus() : dto.getStatus());
            couponMapper.updateById(coupon);
        } else {
            coupon.setRemain(seckill ? dto.getTotal() : null);
            coupon.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
            couponMapper.insert(coupon);
        }

        // ===== 第 3 步：可用范围整组覆盖（先删后插，结果一定等于表单提交的内容）=====
        saveScope(coupon.getId(), scope, dto.getCourseIds(), dto.getStoreIds());

        // ===== 第 4 步：只有秒杀券需要库存预热 =====
        if (seckill && coupon.getStatus() == 1) {
            preheat(coupon);
        }
        log.info("券已保存: couponId={}, type={}, scope={}", coupon.getId(), coupon.getCouponType(), scope);
    }

    @Override
    public void adminEnd(Long id) {
        Coupon coupon = couponMapper.selectById(id);
        if (coupon == null) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }
        coupon.setStatus(2);
        couponMapper.updateById(coupon);
        // 作废后立刻让 Redis 库存失效，正在抢的人下一次 Lua 就会因"键不存在"被判售罄
        redis.delete(RedisKeyConstants.couponStock(id));
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /**
     * 库存预热。
     * <p>★<b>绝不删 {@code coupon:got} 计数</b>：那会把"每人限领 N 张"的资格清空，
     * 领过的人能再领一遍——和秒杀里"重复预热不能删 bought"是同一个坑。
     * 只在设库存，让 Redis 的剩余数跟上管理员改的总量。</p>
     */
    private void preheat(Coupon coupon) {
        long ttl = Math.max(60, Duration.between(LocalDateTime.now(), coupon.getReceiveEndTime()).toSeconds());
        redis.opsForValue().set(RedisKeyConstants.couponStock(coupon.getId()),
                String.valueOf(coupon.getRemain() == null ? 0 : coupon.getRemain()),
                Duration.ofSeconds(ttl));
    }

    /** 按 valid_type 现算这张券的使用截止（★不写库，只用于展示与校验） */
    private LocalDateTime calcValidUntil(Coupon coupon, LocalDateTime receivedAt, LocalDateTime now) {
        if ("DAYS".equals(coupon.getValidType())) {
            if (coupon.getValidDays() == null || receivedAt == null) {
                return null;
            }
            return receivedAt.plusDays(coupon.getValidDays());
        }
        if ("RANGE".equals(coupon.getValidType())) {
            return coupon.getUseEndTime();
        }
        // FOREVER 与没配的：null = 长期有效。过期判断处处都要带 null 分支
        return null;
    }

    /** 整组覆盖范围表。★scope=0 时两张表都清掉，不给"改成全场通用却残留旧课程"留机会 */
    private void saveScope(Long couponId, int scope, List<Long> courseIds, List<Long> storeIds) {
        couponCourseMapper.deleteByCoupon(couponId);
        couponStoreMapper.deleteByCoupon(couponId);
        if (scope == 1 && courseIds != null) {
            for (Long courseId : courseIds) {
                CouponCourse row = new CouponCourse();
                row.setCouponId(couponId);
                row.setCourseId(courseId);
                couponCourseMapper.insert(row);
            }
        }
        if (scope == 2 && storeIds != null) {
            for (Long storeId : storeIds) {
                CouponStore row = new CouponStore();
                row.setCouponId(couponId);
                row.setStoreId(storeId);
                couponStoreMapper.insert(row);
            }
        }
    }

    private LocalDateTime parseTime(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择" + fieldName);
        }
        try {
            return LocalDateTime.parse(text.trim().replace(' ', 'T'));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, fieldName + "格式应为 yyyy-MM-dd HH:mm");
        }
    }

    /** 领券中心的一张券 → VO（含按类型生成的规则条目） */
    private CouponVO toVO(Coupon coupon, int receivedByMe) {
        boolean seckill = "SECKILL".equals(coupon.getCouponType());
        LocalDateTime now = LocalDateTime.now();
        boolean inStock = !seckill || (coupon.getRemain() != null && coupon.getRemain() > 0);
        int limit = coupon.getLimitPerUser() == null ? 0 : coupon.getLimitPerUser();
        boolean withinLimit = limit <= 0 || receivedByMe < limit;

        RuleContext context = RuleContext.builder()
                .couponType(coupon.getCouponType())
                .threshold(coupon.getThreshold())
                .discount(coupon.getDiscount())
                .total(coupon.getTotal())
                .remain(coupon.getRemain())
                .limitPerUser(limit)
                .couponValidDays("DAYS".equals(coupon.getValidType()) ? coupon.getValidDays() : null)
                .useEndTime(coupon.getUseEndTime())
                .scope(coupon.getScope())
                .build();
        String scene = ruleDescribeFactory.couponSceneOf(coupon.getCouponType());

        return CouponVO.builder()
                .couponId(coupon.getId())
                .title(coupon.getTitle())
                .couponType(coupon.getCouponType())
                .couponTypeText(seckill ? "秒杀专享" : "普通券")
                .threshold(coupon.getThreshold())
                .thresholdText(coupon.getThreshold() != null && coupon.getThreshold().signum() > 0
                        ? "满 " + plain(coupon.getThreshold()) + " 可用" : "无门槛")
                .discount(coupon.getDiscount())
                .discountText("¥" + plain(coupon.getDiscount()))
                .total(coupon.getTotal())
                .remain(coupon.getRemain())
                .limitPerUser(limit)
                .received(receivedByMe)
                .receiveEndText(coupon.getReceiveEndTime() == null ? ""
                        : coupon.getReceiveEndTime().format(DAY_TIME) + " 前可领")
                .scope(coupon.getScope())
                .scopeText(scopeText(coupon.getScope()))
                .description(coupon.getDescription())
                .ruleTexts(ruleDescribeFactory.describe(scene, context))
                // ★canReceive 四个条件：上架 + 在领取窗口内 + 有库存 + 没超个人限领
                .canReceive(coupon.getStatus() == 1
                        && !now.isBefore(coupon.getReceiveStartTime())
                        && !now.isAfter(coupon.getReceiveEndTime())
                        && inStock && withinLimit)
                .status(coupon.getStatus())
                .build();
    }

    private String scopeText(Integer scope) {
        if (scope == null || scope == 0) {
            return "全场通用";
        }
        return scope == 1 ? "仅限指定课程" : "仅限指定门店";
    }

    private String statusText(String status) {
        if ("UNUSED".equals(status)) {
            return "未使用";
        }
        return "USED".equals(status) ? "已使用" : "已过期";
    }

    /** 金额去掉多余的 .00，"满 200 可用" 比 "满 200.00 可用" 像人话 */
    private String plain(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    private CouponUsableDTO notUsable(Long recordId, Long couponId, String reason) {
        return CouponUsableDTO.builder()
                .recordId(recordId)
                .couponId(couponId)
                .usable(false)
                .reason(reason)
                .build();
    }
}
