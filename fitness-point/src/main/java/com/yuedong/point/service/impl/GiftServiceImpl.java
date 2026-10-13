package com.yuedong.point.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.point.api.dto.GiftDTO;
import com.yuedong.point.api.dto.GiftSaveDTO;
import com.yuedong.point.mapper.GiftMapper;
import com.yuedong.point.mapper.MemberPointsMapper;
import com.yuedong.point.mapper.PointsLogMapper;
import com.yuedong.point.model.entity.Gift;
import com.yuedong.point.model.entity.MemberPoints;
import com.yuedong.point.model.entity.PointsLog;
import com.yuedong.point.model.vo.GiftVO;
import com.yuedong.point.mq.NotifyPublisher;
import com.yuedong.point.service.GiftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分商城实现：兑换 = "CAS 扣积分 + 原子扣库存"同一事务。
 *
 * <p>事务边界说明：积分表与商品表同库（yd_point），用本地 @Transactional 保证
 * 要么都成功要么都回滚——不需要分布式事务；流水表随事务一并写入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftServiceImpl implements GiftService {

    private final GiftMapper giftMapper;
    private final MemberPointsMapper memberPointsMapper;
    private final PointsLogMapper pointsLogMapper;
    private final NotifyPublisher notifyPublisher;

    @Override
    public List<GiftVO> list() {
        return giftMapper.selectList(new LambdaQueryWrapper<Gift>()
                        .eq(Gift::getStatus, 1).orderByAsc(Gift::getPointsCost))
                .stream()
                .map(g -> GiftVO.builder()
                        .id(g.getId())
                        .name(g.getName())
                        .pointsCost(g.getPointsCost())
                        .stock(g.getStock())
                        .build())
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void exchange(Long userId, Long giftId) {
        // ===== 第 1 步：商品校验（上架 + 有库存） =====
        Gift gift = giftMapper.selectById(giftId);
        if (gift == null || gift.getStatus() != 1) {
            throw new BusinessException(ErrorCode.GIFT_NOT_FOUND);
        }
        if (gift.getStock() <= 0) {
            throw new BusinessException(ErrorCode.GIFT_STOCK_EMPTY);
        }

        // ===== 第 2 步：确保积分账户存在（首次兑换的用户） =====
        ensureMember(userId);

        // ===== 第 3 步：CAS 扣积分（余额不足影响行数为 0） =====
        int deductRows = memberPointsMapper.deductForExchange(userId, gift.getPointsCost());
        if (deductRows == 0) {
            throw new BusinessException(ErrorCode.POINTS_NOT_ENOUGH);
        }

        // ===== 第 4 步：原子扣商品库存（失败抛异常 → 事务回滚积分） =====
        if (giftMapper.deductStock(giftId) == 0) {
            throw new BusinessException(ErrorCode.GIFT_STOCK_EMPTY);
        }

        // ===== 第 5 步：流水（biz_id=商品id+时间戳，配合 UNIQUE 兜底幂等） =====
        PointsLog logRow = new PointsLog();
        logRow.setUserId(userId);
        logRow.setTitle("积分兑换：" + gift.getName());
        logRow.setPoints(-gift.getPointsCost());
        logRow.setBizType("EXCHANGE");
        logRow.setBizId(giftId + ":" + System.currentTimeMillis());
        logRow.setCreatedAt(LocalDateTime.now());
        pointsLogMapper.insert(logRow);

        // ===== 第 6 步：站内通知 =====
        notifyPublisher.publishPointNotify(userId, "兑换成功",
                "已用 " + gift.getPointsCost() + " 积分兑换「" + gift.getName() + "」，请到门店领取。");

        log.info("积分兑换成功: userId={}, giftId={}, cost={}", userId, giftId, gift.getPointsCost());
    }

    private void ensureMember(Long userId) {
        if (memberPointsMapper.selectById(userId) != null) return;
        MemberPoints init = new MemberPoints();
        init.setUserId(userId);
        init.setBalance(0);
        init.setTotalCheckin(0);
        init.setContinuousDays(0);
        init.setUpdatedAt(LocalDateTime.now());
        try {
            memberPointsMapper.insert(init);
        } catch (DuplicateKeyException ignore) {
        }
    }

    // =====================================================================
    // 管理端（经 fitness-admin-server Feign 调用）
    // =====================================================================
    @Override
    public PageDTO<GiftDTO> adminPage(Integer page, Integer size) {
        Page<Gift> result = giftMapper.selectPage(
                new Page<>(page == null ? 1 : page, size == null ? 10 : size),
                new LambdaQueryWrapper<Gift>().orderByAsc(Gift::getId));
        List<GiftDTO> rows = result.getRecords().stream()
                .map(g -> GiftDTO.builder()
                        .id(g.getId())
                        .name(g.getName())
                        .pointsCost(g.getPointsCost())
                        .stock(g.getStock())
                        .status(g.getStatus())
                        .build())
                .toList();
        return PageDTO.of(rows, result.getTotal());
    }

    @Override
    public void adminSave(GiftSaveDTO dto) {
        Gift gift = new Gift();
        gift.setId(dto.getId());
        gift.setName(dto.getName());
        gift.setPointsCost(dto.getPointsCost());
        gift.setStock(dto.getStock());
        if (gift.getId() != null) {
            giftMapper.updateById(gift);
            return;
        }
        gift.setStatus(1);
        giftMapper.insert(gift);
    }

    @Override
    public void addStock(Long giftId, Integer add) {
        Gift gift = giftMapper.selectById(giftId);
        if (gift == null) throw new BusinessException(ErrorCode.GIFT_NOT_FOUND);
        gift.setStock(gift.getStock() + add);
        giftMapper.updateById(gift);
    }
}
