package com.yuedong.order.service.support;

import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CouponClient;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.api.dto.SeckillActiveDTO;
import com.yuedong.order.constant.OrderConstants;
import com.yuedong.order.model.vo.BookingQuoteVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 下单算价（★一份规则，两个入口共用）。
 *
 * <p>项目里有两个地方需要"这笔买卖该付多少钱"：
 * <ul>
 *   <li>{@code /booking/preview}：用户改份数、换券时反复调，只读、不写库；</li>
 *   <li>{@code /booking/create}：真正建单，成交金额以这里算出的为准。</li>
 * </ul>
 * 两者必须走<b>同一个方法</b>。各写一遍的话，试算显示 100、成交按 90 或者反过来，
 * 是这类系统里最难查的一类不一致——它不是报错，而是两个数字都"看起来合理"。</p>
 *
 * <p>★本类<b>不抛"券不可用"</b>：券用不了在试算阶段是一个正常的业务答案
 * （要显示"差 50 元可用"这种话），不是故障。所以结果里给 {@code couponUsable=false}
 * 加一句 reason，由调用方决定：preview 照常返回给用户看，create 才抛异常终止下单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingPriceCalculator {

    private final CourseClient courseClient;
    private final CouponClient couponClient;

    /**
     * 秒杀算价。<b>只覆盖价格这一层，其余规则全部复用普通算价。</b>
     *
     * <p>★可售判定、课程快照、凭证张数（timesGranted）、门店名这些和"是不是秒杀"无关，
     * 所以这里调一次 {@link #quote} 再改价，而不是再写一遍取数与校验。
     * 秒杀和普通购买最容易被写成两套逻辑，之后"普通单约不了课而秒杀单能约"
     * 这类差异就说不清了。</p>
     *
     * <p>★这里<b>不碰库存、不判一人一单</b>：试算是只读的，用户点进确认页不算抢购。
     * 真正的原子扣减在提交下单那一步（{@code SeckillStockGateway}）。</p>
     *
     * @param activityId 前端从秒杀列表带过来的活动 id。允许为 null（只按课程查当前进行中的活动），
     *                   但传了就必须和此刻进行中的活动一致——不一致说明活动刚被换过，
     *                   按"活动已结束"拒绝，不能拿新活动的价格去成交旧活动的单。
     */
    public BookingQuoteVO quoteSeckill(Long userId, Long courseId, Long activityId) {
        Result<SeckillActiveDTO> activeResult = courseClient.activeSeckill(courseId);
        if (activeResult.getCode() != 0 || activeResult.getData() == null) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_ACTIVE);
        }
        SeckillActiveDTO active = activeResult.getData();

        if (activityId != null && !activityId.equals(active.getActivityId())) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_ACTIVE, "该场秒杀已结束，请重新选择");
        }
        // 门店来自活动而不是课程：v2 里一门课可以在多家店卖，抢的是"这家店的名额"
        if (active.getStoreId() == null) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_ACTIVE, "该活动未配置门店，暂不可抢");
        }
        if (active.getSeckillPrice() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该活动未配置秒杀价，请联系门店");
        }

        // 份数固定 1、券固定不用：这两个约束在秒杀域里是规则，不给调用方选择的机会
        BookingQuoteVO quote = quote(userId, courseId, active.getStoreId(), 1, null, false);

        BigDecimal listPrice = quote.getUnitPrice();
        BigDecimal seckillPrice = active.getSeckillPrice();
        quote.setActivityId(active.getActivityId());
        quote.setSeckill(true);
        quote.setSeckillEndTime(active.getEndTime());
        quote.setUnitPrice(seckillPrice);
        // 原价小计留着课程原价：订单详情要显示划线价，这两个数不能合并成一个字段
        quote.setDiscountAmount(listPrice.subtract(seckillPrice).max(BigDecimal.ZERO));
        quote.setPayAmount(seckillPrice.max(BigDecimal.ZERO));
        return quote;
    }

    /**
     * 算一次价。
     *
     * @param rawQuantity     前端传的份数，可以为 null（按 1 处理）
     * @param couponRecordId  选的券，可以为 null
     * @param withCouponList  要不要顺带返回"我手里所有券"的判定——只有试算页要，
     *                        建单时不用（那时券已经定了，多一次跨服务调用纯属浪费）
     */
    public BookingQuoteVO quote(Long userId, Long courseId, Long storeId, Integer rawQuantity,
                       Long couponRecordId, boolean withCouponList) {

        // ===== 第 1 步：份数。★超过上限直接报错，不再静默截断 =====
        // 以前是 Math.min(quantity, 10)：用户点了 20 份，服务端按 10 份建单出票，
        // 不报错也不提示，他要到"我的凭证"才发现少了一半。没报错的错是最难发现的错。
        int quantity = rawQuantity == null || rawQuantity < 1 ? 1 : rawQuantity;
        if (quantity > OrderConstants.MAX_QUANTITY_PER_ITEM) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "一次最多买 " + OrderConstants.MAX_QUANTITY_PER_ITEM + " 份，请分多次下单");
        }

        // ===== 第 2 步：课程快照。一次 RPC 同时拿到课程字段、门店名与可售状态 =====
        Result<CourseDTO> detailResult = courseClient.detail(courseId, storeId);
        if (detailResult.getCode() != 0 || detailResult.getData() == null) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        CourseDTO course = detailResult.getData();

        // ===== 第 3 步：可售判定 =====
        // "课程存在"不等于"能买"：展示型课程没有购买这个动作，
        // 而不在可售窗口内的课买下去会拿到一张用不了的凭证
        if (!"SALE".equals(course.getCourseKind())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该课程为展示内容，无需购买");
        }
        if (!Boolean.TRUE.equals(course.getOnSale())) {
            throw new BusinessException(ErrorCode.COURSE_NOT_ON_SALE,
                    "「" + course.getName() + "」当前不可购买（未开售或已停售）");
        }
        if (course.getPrice() == null) {
            // 管理端漏配价格。宁可当场拒绝，也不要建出一张 0 元单
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该课程未配置价格，请联系门店");
        }

        BigDecimal unitPrice = course.getPrice();
        BigDecimal originalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));

        // ===== 第 4 步：选了券就判一次。★不可用不在这里抛异常，见类注释 =====
        Long appliedCoupon = couponRecordId;
        boolean couponUsable = false;
        String couponTitle = null;
        String couponReason = null;
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (appliedCoupon != null) {
            Result<CouponUsableDTO> couponResult =
                    couponClient.info(appliedCoupon, userId, originalAmount, courseId, storeId);
            CouponUsableDTO coupon = couponResult.getData();
            if (couponResult.getCode() != 0 || coupon == null) {
                couponReason = ErrorCode.COUPON_NOT_USABLE.getMessage();
            } else {
                couponUsable = coupon.isUsable();
                couponTitle = coupon.getTitle();
                couponReason = coupon.getReason();
                // ★只有可用的券才参与抵扣。"券不可用但按它的额度减一下"是不存在的状态
                discountAmount = couponUsable && coupon.getDiscount() != null
                        ? coupon.getDiscount() : BigDecimal.ZERO;
            }
        }

        // 实付不能为负：满 100 减 120 这类配置错误的券，宁可少收也不能倒找钱
        BigDecimal payAmount = originalAmount.subtract(discountAmount).max(BigDecimal.ZERO);

        // ===== 第 5 步：试算页还要一份"我手里所有券"的判定 =====
        // 一次跨服务调用换 N 次，别在这里循环调 info()（每张单一次 RPC，10 张券就是 10 次往返）
        List<CouponUsableDTO> options = new ArrayList<>();
        if (withCouponList) {
            Result<List<CouponUsableDTO>> listResult =
                    couponClient.usableList(userId, courseId, storeId, originalAmount);
            if (listResult.getCode() == 0 && listResult.getData() != null) {
                options = listResult.getData();
            }
        }

        // UNLIMITED 存 null 表示不限次；TIMES 用管理员配课时时定的 times_per_buy（★不是固定 1）
        Integer timesGranted = "UNLIMITED".equals(course.getSaleType()) ? null
                : (course.getTimesPerBuy() == null || course.getTimesPerBuy() < 1 ? 1 : course.getTimesPerBuy());

        return BookingQuoteVO.builder()
                .course(course)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .originalAmount(originalAmount)
                .discountAmount(discountAmount)
                .payAmount(payAmount)
                .timesGranted(timesGranted)
                .couponRecordId(appliedCoupon)
                .couponUsable(couponUsable)
                .couponTitle(couponTitle)
                .couponReason(couponReason)
                .couponOptions(options)
                .build();
    }
}
