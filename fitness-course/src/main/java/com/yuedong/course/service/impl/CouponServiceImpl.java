package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.constant.RedisKeyConstants;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.mapper.CouponMapper;
import com.yuedong.course.mapper.UserCouponMapper;
import com.yuedong.course.model.entity.Coupon;
import com.yuedong.course.model.entity.UserCoupon;
import com.yuedong.course.model.vo.CouponVO;
import com.yuedong.course.model.vo.MyCouponVO;
import com.yuedong.course.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 优惠券实现 —— 领券与秒杀同构（Lua 原子"库存+一人一张"），
 * 差别只在"抢到之后"：秒杀建订单，领券写持券记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private static final String LUA_PATH = "lua/coupon_receive.lua";

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final StringRedisTemplate redis;

    /** 领券 Lua 脚本（启动加载一次） */
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
        List<Coupon> coupons = couponMapper.selectList(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, 1)
                .le(Coupon::getStartTime, now)
                .ge(Coupon::getEndTime, now)
                .orderByDesc(Coupon::getId));
        // 当前用户已领的券 id 集合（领券中心标记"已领取"）
        Set<Long> receivedIds = userId == null ? Set.of()
                : userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, userId)).stream()
                        .map(UserCoupon::getCouponId).collect(Collectors.toSet());
        return coupons.stream()
                .map(c -> toVO(c, receivedIds.contains(c.getId())))
                .toList();
    }

    @Override
    public List<MyCouponVO> myCoupons(Long userId) {
        List<UserCoupon> records = userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId));
        if (records.isEmpty()) return List.of();

        Map<Long, Coupon> couponMap = couponMapper.selectBatchIds(
                        records.stream().map(UserCoupon::getCouponId).toList()).stream()
                .collect(Collectors.toMap(Coupon::getId, Function.identity()));

        return records.stream()
                .filter(r -> couponMap.containsKey(r.getCouponId()))
                .map(r -> {
                    Coupon c = couponMap.get(r.getCouponId());
                    boolean usable = "UNUSED".equals(r.getStatus())
                            && c.getStatus() == 1
                            && c.getEndTime().isAfter(LocalDateTime.now());
                    return MyCouponVO.builder()
                            .recordId(r.getId())
                            .title(c.getTitle())
                            .threshold(c.getThreshold())
                            .discount(c.getDiscount())
                            .status(r.getStatus())
                            .endTime(c.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                            .usable(usable)
                            .build();
                })
                .toList();
    }

    // =====================================================================
    // 领券：Lua 原子扣减（库存 + 一人一张）→ 写持券记录 → 扣 DB 库存
    // =====================================================================
    @Override
    public void receive(Long userId, Long couponId) {
        // ===== 第 1 步：券有效性校验（上架 + 在领取时间窗内） =====
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || coupon.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getStartTime()) || now.isAfter(coupon.getEndTime())) {
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE, "不在领取时间内");
        }

        // ===== 第 2 步：Lua 原子校验"库存 + 一人一张"并扣减 =====
        long ttl = Math.max(60, Duration.between(now, coupon.getEndTime()).toSeconds());
        Long code = redis.execute(receiveScript,
                List.of(RedisKeyConstants.couponStock(couponId), RedisKeyConstants.couponGot(couponId)),
                String.valueOf(userId), String.valueOf(ttl));
        if (code != null && code == 1L) {
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }
        if (code != null && code == 2L) {
            throw new BusinessException(ErrorCode.COUPON_DUPLICATE);
        }

        // ===== 第 3 步：写持券记录（Redis 已扣，DB 库存最终一致） =====
        UserCoupon record = new UserCoupon();
        record.setUserId(userId);
        record.setCouponId(couponId);
        record.setStatus("UNUSED");
        record.setReceivedAt(LocalDateTime.now());
        try {
            userCouponMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发领取唯一索引兜底：回补 Redis 并提示
            redis.opsForValue().increment(RedisKeyConstants.couponStock(couponId));
            redis.opsForSet().remove(RedisKeyConstants.couponGot(couponId), String.valueOf(userId));
            throw new BusinessException(ErrorCode.COUPON_DUPLICATE);
        }
        couponMapper.deductRemain(couponId);
        log.info("领券成功: userId={}, couponId={}", userId, couponId);
    }

    // =====================================================================
    // 内部接口：订单服务的用券校验 / 核销 / 退券
    // =====================================================================
    @Override
    public CouponUsableDTO usableInfo(Long recordId, Long userId, BigDecimal amount) {
        UserCoupon record = userCouponMapper.selectById(recordId);
        if (record == null || !record.getUserId().equals(userId)) {
            return notUsable(recordId, "优惠券不存在");
        }
        Coupon coupon = couponMapper.selectById(record.getCouponId());
        if (coupon == null || coupon.getStatus() != 1) {
            return notUsable(recordId, "优惠券已下架");
        }
        if (!"UNUSED".equals(record.getStatus())) {
            return notUsable(recordId, "优惠券已使用");
        }
        if (coupon.getEndTime().isBefore(LocalDateTime.now())) {
            return notUsable(recordId, "优惠券已过期");
        }
        if (amount.compareTo(coupon.getThreshold()) < 0) {
            return notUsable(recordId, "未达到使用门槛（满 " + coupon.getThreshold() + " 元可用）");
        }
        return CouponUsableDTO.builder()
                .recordId(recordId).title(coupon.getTitle())
                .discount(coupon.getDiscount()).usable(true).build();
    }

    @Override
    public boolean use(Long recordId, Long userId, String orderNo) {
        // CAS：仅 UNUSED 可核销，绑定订单号；订单取消/超时后会退回
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
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Coupon> result =
                couponMapper.selectPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                                page == null ? 1 : page, size == null ? 10 : size),
                        new LambdaQueryWrapper<Coupon>().orderByDesc(Coupon::getId));
        List<CouponAdminDTO> rows = result.getRecords().stream()
                .map(c -> CouponAdminDTO.builder()
                        .id(c.getId())
                        .title(c.getTitle())
                        .threshold(c.getThreshold())
                        .discount(c.getDiscount())
                        .total(c.getTotal())
                        .remain(c.getRemain())
                        .limitPerUser(c.getLimitPerUser())
                        .startTime(c.getStartTime())
                        .endTime(c.getEndTime())
                        .status(c.getStatus())
                        .build())
                .toList();
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public void adminSave(CouponSaveDTO dto) {
        if (dto.getDiscount().compareTo(dto.getThreshold()) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "抵扣金额不能高于使用门槛");
        }
        LocalDateTime start = LocalDateTime.parse(dto.getStartTime().replace(' ', 'T'));
        LocalDateTime end = LocalDateTime.parse(dto.getEndTime().replace(' ', 'T'));
        if (!end.isAfter(start)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "截止时间必须晚于开始时间");
        }
        if (dto.getId() != null) {
            Coupon coupon = couponMapper.selectById(dto.getId());
            if (coupon == null) throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
            coupon.setTitle(dto.getTitle());
            coupon.setThreshold(dto.getThreshold());
            coupon.setDiscount(dto.getDiscount());
            coupon.setTotal(dto.getTotal());
            coupon.setStartTime(start);
            coupon.setEndTime(end);
            couponMapper.updateById(coupon);
            preheat(coupon);
            return;
        }
        Coupon coupon = new Coupon();
        coupon.setTitle(dto.getTitle());
        coupon.setThreshold(dto.getThreshold());
        coupon.setDiscount(dto.getDiscount());
        coupon.setTotal(dto.getTotal());
        coupon.setRemain(dto.getTotal());
        coupon.setLimitPerUser(1);
        coupon.setStartTime(start);
        coupon.setEndTime(end);
        coupon.setStatus(1);
        couponMapper.insert(coupon);
        preheat(coupon); // 新券库存直接预热进 Redis
    }

    @Override
    public void adminEnd(Long id) {
        Coupon coupon = couponMapper.selectById(id);
        if (coupon == null) throw new BusinessException(ErrorCode.COUPON_NOT_FOUND);
        coupon.setStatus(2);
        couponMapper.updateById(coupon);
        redis.delete(RedisKeyConstants.couponStock(id)); // 作废后 Redis 库存立即失效
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 券库存预热进 Redis（创建/编辑时调用；TTL 到领取截止） */
    private void preheat(Coupon coupon) {
        long ttl = Math.max(60, Duration.between(LocalDateTime.now(), coupon.getEndTime()).toSeconds());
        redis.opsForValue().set(RedisKeyConstants.couponStock(coupon.getId()), String.valueOf(coupon.getRemain()), Duration.ofSeconds(ttl));
        redis.delete(RedisKeyConstants.couponGot(coupon.getId()));
    }

    private CouponVO toVO(Coupon c, boolean receivedByMe) {
        return CouponVO.builder()
                .id(c.getId())
                .title(c.getTitle())
                .threshold(c.getThreshold())
                .discount(c.getDiscount())
                .total(c.getTotal())
                .remain(c.getRemain())
                .endTime(c.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .status(c.getStatus())
                .receivedByMe(receivedByMe)
                .build();
    }

    private CouponUsableDTO notUsable(Long recordId, String reason) {
        return CouponUsableDTO.builder().recordId(recordId).usable(false).reason(reason).build();
    }
}
