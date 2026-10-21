package com.yuedong.order.service;

import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 秒杀名额回补器：<b>Redis 门神 + DB 账本</b>两处一起还。
 *
 * <p>★为什么需要这个类：秒杀建单时 Redis 先扣、课程服务再扣 DB {@code remain}。
 * 以前取消/超时只回补 Redis，DB {@code remain} 只减不增，未支付订单越多，
 * DB 越早归零，之后新用户全部被判定"售罄"，活动实际少卖。</p>
 *
 * <p>★为什么失败只记日志、不抛异常：调用这个方法时，订单状态已经被 CAS 改成
 * CANCELED（或者本地事务已经失败），抛给用户只会让他重复点击，
 * 而重复点击不会让远端回补成功。这里把两处都尝试一遍，失败留给对账/人工处理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillQuotaRestorer {

    private final SeckillStockGateway seckillStock;
    private final CourseClient courseClient;

    /**
     * @param activityId 活动 id
     * @param userId     抢购人（Redis 资格集合要按人移除）
     * @param orderNo    订单号，只用于日志定位
     * @param source     回补来源：用户主动取消 / 超时取消 / 建单落库失败
     */
    public void restore(Long activityId, Long userId, String orderNo, String source) {
        // ===== 第一处：Redis（库存 +1、资格集合移除这个人）=====
        try {
            seckillStock.restore(activityId, userId);
            log.info("秒杀 Redis 名额已回补: activityId={}, orderNo={}, 来源={}",
                    activityId, orderNo, source);
        } catch (Exception e) {
            log.error("秒杀 Redis 名额回补失败，需人工对账: activityId={}, orderNo={}, 来源={}",
                    activityId, orderNo, source, e);
        }

        // ===== 第二处：课程服务的 DB remain（账本）=====
        try {
            Result<Boolean> result = courseClient.restoreRemain(activityId);
            if (result.getCode() != 0 || !Boolean.TRUE.equals(result.getData())) {
                log.error("秒杀 DB 名额回补失败（活动不存在或库存已满），需人工对账: " +
                                "activityId={}, orderNo={}, 来源={}",
                        activityId, orderNo, source);
            }
        } catch (Exception e) {
            log.error("秒杀 DB 名额回补调用异常，需人工对账: activityId={}, orderNo={}, 来源={}",
                    activityId, orderNo, source, e);
        }
    }
}
