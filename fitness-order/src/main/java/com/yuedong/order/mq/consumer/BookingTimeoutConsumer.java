package com.yuedong.order.mq.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.constant.MqConstants;
import com.yuedong.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuedong.course.api.client.CouponClient;
import com.yuedong.order.constant.BookingStatus;
import com.yuedong.order.mapper.BookingMapper;
import com.yuedong.order.model.entity.Booking;
import com.yuedong.order.mq.NotifyPublisher;
import com.yuedong.order.mq.RabbitConfig;
import com.yuedong.order.mq.message.OrderTimeoutMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 订单超时取消消费者：下单 15 分钟后仍未支付 → 自动取消并退回优惠券。
 *
 * <p>★延迟消息不需要"撤回"：本方法只用一条 CAS（{@code WHERE status='UNPAID'}）决定要不要动手。
 * 用户恰好在这时支付成功了，影响行数就是 0，这里什么都不做。
 * 这比"支付成功后去撤销延迟消息"简单得多也不会出错——RabbitMQ 本来也撤不回已投递的消息。</p>
 *
 * <p>★普通单在这里<b>没有释放名额这一步</b>：购买不占时段，时段占用发生在创建预约时。
 * 旧代码那段 {@code releaseSchedule/release} 调用既是跨服务写、又不检查返回值，
 * 是"取消一次就凭空多出一个空位"这类 bug 的来源。</p>
 *
 * <p>★但<b>秒杀单要退名额</b>（见下面的回补）：活动名额是购买时就占住的真实稀缺资源，
 * 不退回就是"有人抢到不付 → 这件商品永久消失，而且他自己也抢不了第二次"。
 * 回补只发生在 CAS 影响 1 行的分支里——<b>状态迁移的结果就是幂等锚点</b>，
 * 用户恰好在这时支付成功（影响 0 行）时绝不会多还一个名额。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingTimeoutConsumer {

    private final BookingMapper bookingMapper;
    private final CouponClient couponClient;
    private final ObjectMapper objectMapper;
    private final NotifyPublisher notifyPublisher;

    /** 秒杀名额回补（Redis 库存 +1、资格集合摘人，课程 DB remain 也 +1） */
    private final com.yuedong.order.service.support.SeckillQuotaRestorer seckillQuotaRestorer;

    // ★ackMode=MANUAL：方法体自己 basicAck/basicNack，容器默认是 AUTO 会再 ack 一次，
    //   第二次 ack 的是已经不存在的 delivery tag → broker 关信道（PRECONDITION_FAILED
    //   unknown delivery tag）→ 消费者反复重启，超时取消这条链路实际上断掉了。
    @RabbitListener(queues = MqConstants.ORDER_TIMEOUT_QUEUE, ackMode = "MANUAL")
    public void onMessage(String json, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            OrderTimeoutMessage msg = objectMapper.readValue(json, OrderTimeoutMessage.class);

            // ===== 第 1 步：状态预判。不为 UNPAID 的直接放过，省一次写操作 =====
            Booking booking = bookingMapper.selectOne(new LambdaQueryWrapper<Booking>()
                    .eq(Booking::getOrderNo, msg.getOrderNo()));
            if (booking == null || !BookingStatus.UNPAID.equals(booking.getStatus())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            // ===== 第 2 步：CAS 取消。并发下（用户正好在支付）只有一个能改成功 =====
            int rows = bookingMapper.cancelIfUnpaid(msg.getOrderNo(), LocalDateTime.now());
            if (rows > 0) {
                // ===== 第 3 步：秒杀单先退名额 =====
                // ★顺序上放在退券之前：名额是活动侧的稀缺资源，越早回去越少丢单。
                //   Redis 与课程 DB remain 两处都回补；只回补 Redis 会让 DB 名额永久少卖。
                //   回补发生在 CAS 影响 1 行的分支里，重复投递不会多还。
                boolean fromSeckill = booking.getActivityId() != null;
                if (fromSeckill) {
                    // ★用 booking.getUserId() 而不是消息里的 userId：
                    //   订单行才是事实，消息体只是传输载体。
                    seckillQuotaRestorer.restore(booking.getActivityId(), booking.getUserId(),
                            msg.getOrderNo(), "超时取消");
                }

                // ===== 第 4 步：退券 + 站内通知 =====
                // ★检查退券结果：旧代码连返回值都不看，券没退成也当成功，
                //   用户之后会发现"券显示已使用"却找不到对应订单
                if (booking.getCouponRecordId() != null) {
                    try {
                        // 带订单号退券：只退“确实绑定在这张订单上”的券，防止跨单误退
                        Result<Boolean> refundResult = couponClient.refund(
                                booking.getCouponRecordId(), msg.getOrderNo());
                        if (refundResult.getCode() != 0 || !Boolean.TRUE.equals(refundResult.getData())) {
                            log.error("超时取消后退券失败，需人工核对: orderNo={}, couponRecordId={}",
                                    msg.getOrderNo(), booking.getCouponRecordId());
                        }
                    } catch (Exception e) {
                        // 订单已经取消（已提交），这里抛不出去也修不了，只能留痕
                        log.error("超时取消退券异常: orderNo={}", msg.getOrderNo(), e);
                    }
                }

                boolean usedCoupon = booking.getDiscountAmount() != null
                        && booking.getDiscountAmount().compareTo(java.math.BigDecimal.ZERO) > 0;
                notifyPublisher.publishBookingNotify(msg.getUserId(), "订单已自动取消",
                        "「" + booking.getCourseName() + "」的订单超时未支付，已自动取消"
                                + (usedCoupon ? "，优惠券已退回。"
                                    : fromSeckill ? "，秒杀名额已退回，你可以重新抢购。" : "。"));
                log.info("订单超时自动取消: orderNo={}", msg.getOrderNo());
            }

            // 无论有没有真的取消，这条消息的使命都完成了 —— 必须 ack，否则会无限重投
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("订单超时取消处理失败: {}", json, e);
            // ★改掉一个真问题：原来用 requeue=true，消息立刻回到队列头部，
            //   如果失败原因是数据本身（毒消息），就会<b>无限立即重投>把消费者线程打满</b>。
            //   改成 requeue=false 让它进死信队列（RabbitConfig 已给该队列挂上 DLX），
            //   死信留着事后排查，而不是把线上资源耗光。
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
