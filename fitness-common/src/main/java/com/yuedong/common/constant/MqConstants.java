package com.yuedong.common.constant;

/**
 * MQ 拓扑统一管理（与《后端架构设计》§7 的拓扑表一一对应）。
 *
 * <p>跨服务声明的队列（生产方声明 + 消费方绑定）必须共享同一常量来源：
 * 例如 q.point.course.finished 由 course 声明、point 消费绑定，两边各写一份字符串会改名不同步。</p>
 */
public final class MqConstants {

    private MqConstants() {
    }

    // ==================== RabbitMQ：订单域（order 服务） ====================
    // ★秒杀不再走"异步建单"，所以这里没有 ex.order.seckill 那组资源了：
    //   提交时订单已经同步落库，没有任何事需要一条消息去驱动建单。
    //   常量删干净是有意的——留着没生产者的拓扑名，下一次改队列参数时
    //   会误以为"这条队列还在用"，进而做出错误的兼容处理。

    /** 订单延迟交换机（x-delayed-message 插件，15 分钟未支付自动取消） */
    public static final String ORDER_DELAY_EXCHANGE = "ex.order.delay";

    /** 订单超时队列 */
    public static final String ORDER_TIMEOUT_QUEUE = "q.order.timeout";

    /** 订单超时路由键 */
    public static final String ORDER_TIMEOUT_KEY = "order.timeout";

    /** 订单死信交换机（消费失败兜底，见下面两条 DLQ） */
    public static final String ORDER_DLX = "ex.order.dlx";

    /**
     * 延迟取消队列的死信队列（P0-10）。
     * <p>★为什么每条业务队列都要有自己的 DLQ 而不是共用一个：
     * 死信里要能看出"它是哪条链路进来的"，共用一个就得靠消息体反解，
     * 而进死信的原因常常正是消息体坏了。</p>
     */
    public static final String ORDER_TIMEOUT_DLQ = "q.order.timeout.dlq";

    /** 延迟取消死信路由键 */
    public static final String ORDER_TIMEOUT_DLQ_KEY = "order.timeout.dlq";

    // ==================== RabbitMQ：跨服务事件（course → point） ====================

    /** 完课事件交换机（direct） */
    public static final String COURSE_EVENT_EXCHANGE = "ex.course.event";

    /** 完课积分队列（course 声明，point 绑定消费） */
    public static final String POINT_COURSE_FINISHED_QUEUE = "q.point.course.finished";

    /** 完课事件路由键 */
    public static final String COURSE_FINISHED_KEY = "course.finished";

    /** 完课事件死信交换机（point 消费异常兜底） */
    public static final String POINT_DLX = "ex.point.dlx";

    /** 完课事件死信队列 */
    public static final String POINT_COURSE_FINISHED_DLQ = "q.point.course.finished.dlq";

    /** 完课死信路由键 */
    public static final String POINT_DLQ_KEY = "point.dlq";

    // ==================== RabbitMQ：站内通知（各业务服务 → user） ====================

    /** 站内通知交换机（topic，user 声明并消费 q.user.notify） */
    public static final String NOTIFY_EXCHANGE = "ex.notify.exchange";

    /** 站内通知队列（user 服务） */
    public static final String USER_NOTIFY_QUEUE = "q.user.notify";

    /** 通知路由键（topic 模式按业务域分发） */
    public static final String ROUTING_NOTIFY_BOOKING = "notify.booking";
    public static final String ROUTING_NOTIFY_POINT = "notify.point";
    public static final String ROUTING_NOTIFY_COMMUNITY = "notify.community";

    // ==================== Kafka：行为埋点流（behavior 服务） ====================

    /** 埋点事件 topic，3 分区，key=deviceId 保证单设备顺序 */
    public static final String BEHAVIOR_TOPIC = "yd-behavior-event";
}
