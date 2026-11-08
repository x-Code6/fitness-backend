# 悦动健身 · 后端

「悦动健身 —— 课程预约与运动社区平台」的微服务后端，覆盖课程购买、教练预约、限时秒杀、优惠券、运动社区、积分打卡、身体数据、行为埋点与运营管理后台。

## 项目简介

面向健身房业务的线上预约与社区平台，用户端是移动端 H5、运营端是 Web 管理后台，两者都只通过网关的 `/api/**` 访问本仓库提供的服务。业务链路按「买课 → 获得凭证 → 约教练 → 上课 → 评价」串起来，另有点赞/评论/关注构成的运动社区、打卡积分与排行榜、行为埋点反哺首页推荐。

后端拆成 9 个可独立启动的业务服务 + 1 个公共模块 + 4 个 Feign 契约模块，每个服务独占一个 MySQL 库，跨服务协作只走 OpenFeign 同步调用或消息队列异步事件，不做跨库 JOIN。

- **170 个接口**：对外 78（24 个 Controller）· 管理端 39（14 个）· 服务间 53（11 个 `/inner/**`）
- **8 个 MySQL 业务库 · 39 张表**，另有 MongoDB 存储每日身体记录
- **24 个 Redis Key**：缓存、登录令牌白名单、打卡位图、排行榜 ZSet、门店 GEO、幂等锁
- **5 个定时任务 · 5 个消息消费者**，RabbitMQ 与 Kafka 双消息队列按场景分工

> 本仓库只含后端服务、建表脚本与中间件编排；用户端 H5 与管理端前端是独立仓库，架构图中作为上游调用方出现。

## 技术栈

| 分类 | 选型 |
|---|---|
| 语言 / 构建 | JDK 17 · Maven（父 POM 统一依赖仲裁与插件配置） |
| 微服务框架 | Spring Boot 3.3.12 · Spring Cloud 2023.0.3 · Spring Cloud Alibaba 2023.0.3.3 |
| 微服务组件 | Spring Cloud Gateway（WebFlux，系统唯一入口）· Nacos 2.4（注册中心）· OpenFeign + LoadBalancer（服务间调用） |
| 数据访问 | MyBatis-Plus 3.5.7 · MySQL 8（每服务独立库）· MongoDB 7（spring-data-mongodb） |
| 缓存 / 分布式锁 | Redis 7（String / Hash / Bitmap / ZSet / GEO）· Redisson（缓存重建互斥锁） |
| 消息队列 | RabbitMQ 3.13 + x-delayed-message 延迟插件（业务消息）· Kafka 3.9 KRaft 单节点（行为埋点流） |
| 认证与安全 | JJWT 0.12（用户端 / 管理端双套密钥）· BCrypt 密码散列（spring-security-crypto）· jakarta validation 参数校验 |
| 部署 | Docker Compose 编排中间件 · Maven fat-jar（spring-boot-maven-plugin repackage） |

## 总体架构

```text
 用户端 H5（前端仓库）           管理端（前端仓库）
         └──────────────┬──────────────┘
                        ▼  /api/**
                 Nginx（反向代理 + 静态托管）
                        ▼
               fitness-gateway :8080
     路由 · JWT 验签 · jti 令牌白名单 · X-User-Id 透传 · CORS
                        ▼  Nacos 服务发现（lb://服务名）
 ┌─────────┬─────────┬─────────┬─────────┬───────────┐
 │  user   │ course  │  order  │  point  │ community │
 │  :8101  │  :8102  │  :8103  │  :8104  │   :8105   │
 └─────────┴─────────┴─────────┴─────────┴───────────┘
 ┌─────────┬─────────┬─────────┬─────────┬───────────┐
 │behavior │  admin  │  file   │  common │ *-api ×4  │
 │  :8106  │  :8107  │  :8108  │ 公共模块 │ Feign 契约 │
 └─────────┴─────────┴─────────┴─────────┴───────────┘
   服务间协作 = OpenFeign 同步调用（/inner/**，网关不可达）
             + RabbitMQ / Kafka 异步事件（松耦合）

 中间件：MySQL 8（8 库）· Redis 7 · RabbitMQ 3.13 · Kafka 3.9 · MongoDB 7 · Nacos 2.4
```

### 服务拆分

| 模块 | 端口 | 存储 | 职责 |
|---|---|---|---|
| fitness-gateway | 8080 | Redis | 唯一对外入口：路由转发、JWT 验签、jti 令牌白名单、透传 `X-User-Id`、CORS、硬拒外部访问 `/inner/**` |
| fitness-user | 8101 | yd_user + Mongo yd_profile | 注册 / 账号密码登录 / JWT 签发与吊销 / 用户资料 / 站内消息中心 / 身体数据与健身档案 |
| fitness-course | 8102 | yd_course | 课程列表与详情缓存 / 门店 GEO / 教练 / 跟练视频与进度 / 秒杀活动 / 优惠券 / 展示课评论 |
| fitness-order | 8103 | yd_order | 下单试算与建单 / 模拟支付与出票 / 预约时段 / 评价与评分统计 / 秒杀下单 / 经营看板统计 |
| fitness-point | 8104 | yd_point | 每日打卡 / 积分流水 / 积分商城兑换 / 月度排行榜与赛季归档 |
| fitness-community | 8105 | yd_community | 动态发布（三档可见性）/ Feed 流 / 点赞收藏 / 两级评论 / 关注 / 浏览量记账 |
| fitness-behavior | 8106 | yd_behavior | 埋点批量上报（Kafka 生产）/ 消费落库 / 课程点击热度榜 |
| fitness-admin-server | 8107 | yd_admin | 管理端 BFF：RBAC 登录（图形验证码 + BCrypt）/ 操作日志 / 跨服务聚合与编排 |
| fitness-file | 8108 | yd_file | 图片上传（四道安全校验）与 `/uploads/**` 静态读取，管理端与用户端共用一套落盘规则 |
| fitness-common | — | — | 统一响应信封 / 五段错误码 / 全局异常 / JWT 工具 / UserContext / `@Idempotent` 幂等注解 |
| fitness-*-api × 4 | — | — | 服务间 Feign 契约（接口 + DTO）。依赖方向只允许「服务 → 别人的 *-api」，Maven 不成环、启动互不等待 |

## 核心功能与技术实现

### 1. 认证与网关

- **JWT + jti 令牌白名单（fail-close）**：登录签发 JWT 时将 `jti` 写入 Redis（`login:token:{jti}`，TTL 与令牌一致），网关验签后必须查到白名单才放行；禁用用户时改状态 + 批量删除该用户全部 jti，**已登录的会话立即失效**。
- **用户端 / 管理端双套密钥**：管理端 JWT 由 admin-server 用独立密钥签发，网关对 `/api/admin/**` 放行、鉴权落在 admin-server 拦截器内，网关不耦合两套密钥与两个 key 空间。
- **内部接口保护**：服务间调用统一走 `/inner/**` 前缀，网关第一步硬拒外部请求；白名单匹配使用 `AntPathMatcher`（Ant 风格 glob），精确放行登录、游客只读接口与社区公开读。
- **身份传递**：下游服务不自行解析 token，只读网关注入的 `X-User-Id`（`UserContext`）；所有 `userId` 入参一律取自上下文，不信任前端。

### 2. 课程与缓存（course）

- **缓存三件套**（课程详情）：
  - 防雪崩：物理 TTL = 逻辑过期 × 2 + 随机抖动；
  - 防击穿：逻辑过期 + Redisson 互斥锁 + 线程池异步重建，重建期间返回旧值；
  - 防穿透：查空结果写空值缓存（TTL 2 分钟）。
- **实时数不进缓存**：评分、评论数这类「新请求就该变的数」不写入缓存对象，每次响应前单独查询，避免「评了却看不见」。
- **门店 GEO**：门店坐标写入 Redis GEO（启动时 `ApplicationRunner` 幂等重建 + 门店保存后重载），附近门店 `GEOSEARCH` 按距离升序返回。
- **规则文案策略模式**：购买规则文案（有效期、限购等）由后端 `rule/` 包 5 个策略产出（可售课按次 / 不限次、展示课、普通券、秒杀券），同一场景重复注册直接启动失败；前端只渲染不下发字典。
- **跟练视频进度**：进度只增不减（`WHERE progress < 新值`）+ 防刷速率校验（增量超过真实耗时的 1.5 倍拒绝）；完课事件只在 `finished` 由 false 翻 true 的那一次发出，杜绝重复加分。

### 3. 交易与履约（order）

- **三轴状态机**：钱（订单 `UNPAID → PAID → CANCELED / REFUNDED`）、权益（凭证 `ACTIVE → USED_UP / EXPIRED`）、时段（预约 `BOOKED → FINISHED / CANCELED`）分三张表，每个动作只推进自己那条轴——取消预约不动订单、凭证用完不改订单、核销不扣次。
- **试算与成交同源**：下单页试算（`/booking/preview`）与建单（`/booking/create`）走同一个 `quote()` 方法，页面上四个金额、券可用性与最终成交必然一致；券的五条判定规则（金额门槛 / 使用时间窗 / 课程白名单 / 门店白名单 / 已使用状态）只存在于服务端一份。
- **一次性下单令牌**：preview 预分配 `orderNo` 并写入 `order:create:token:{userId}:{orderNo}`（TTL 10 分钟），create 第一步用 **GETDEL 原子消费**；连点两下、超时重发、过期页面提交都只有第一次能过，最终防线是订单表唯一约束。
- **支付与出票同事务**：支付 CAS 置 PAID（`WHERE status='UNPAID'`）与按份数生成 N 张独立凭证在**同一本地事务**，杜绝「付了钱没凭证 / 没付钱有凭证」；凭证不合并成次数总单，退款与过期都按单张处理。
- **15 分钟未支付自动取消**：下单成功投递 RabbitMQ **延迟消息**（x-delayed-message 插件）；消费时只做状态 CAS（`UPDATE ... WHERE status='UNPAID'`），已付款则影响 0 行自然什么都不做——**不撤回消息**，比撤回简单且不会出错。
- **预约时段三趟构建**：① 门店营业时间 ∩ 教练工时求可约区间 → ② 查该教练当天已占格子 → ③ 按课程课时长做不可拆分组装（2 小时的课不能跨两个 1 小时空格拼凑）；每个格子带原因码（CLOSED / PASSED / BOOKED / SELF_USED / GROUP_UNAVAILABLE / NO_ENTITLEMENT）。
- **一格一行防并发**：`t_coach_busy` 每小时格一行，把「区间重叠判断」变成「主键冲突判断」，并发抢同一时段靠 `uk_coach_start` 唯一索引兜底；占格按开始时间**升序插入**，让并发请求以同一顺序拿锁，把死锁概率降到最低。
- **取消规则固化在列上**：`cancel_deadline`（提前 1 小时可取消）入库时算好，取消 SQL 直接 `WHERE cancel_deadline > NOW()`，Java 不算时间、不依赖应用与数据库时钟一致。
- **到点结算**：每分钟扫 `end_time <= NOW() AND status='BOOKED'` 置 FINISHED 并扣减凭证一次——口径是**下课才算消费**，取消的永远进不来，所以「取消不扣次」不需要额外分支。
- **评价与评分**：门槛为该订单全部凭证不再 ACTIVE（用完或过期）；一单一评（唯一索引）；写侧与订单同库同事务，零分布式事务。三张统计表用 `INSERT ... ON DUPLICATE KEY UPDATE` 增量维护 sum/count；展示用算术平均，排序与优选标签用**贝叶斯分现算不落库**。

### 4. 秒杀（course + order 协作）

```text
[每 30 秒] 定时扫描将开始的活动 → 先 SET Redis 库存，再 CAS 翻开活动状态
           （顺序保证不存在"状态开放但库存未就绪"的空窗）
POST /seckill/book（order 服务）
  ① Feign 查进行中活动 → ② 预写排队结果 QUEUEING
  ③ Lua 原子：库存 DECR + 一人一单 SISMEMBER/SADD（失败码区分售罄/重复）
  ④ 投 RabbitMQ（q.order.seckill.create）削峰 → 返回排队号 seqId
消费者：幂等检查 → 建单（DB 扣份数）→ 结果写 Redis → 失败回补库存并进死信队列
GET /seckill/result?seqId  前端每 500ms 轮询：QUEUEING / SUCCESS(订单号) / FAIL(原因)
```

- 活动列表与课程信息联查归 course，抢课与轮询结果归 order，网关按精确路径优先路由。
- Redis 库存为准、DB `remain` 是账本；消费失败 Lua INCR 回补。

### 5. 优惠券（course）

- **双路径领取**：秒杀券走 Lua（Hash 计数判限领 N 张 + 库存 DECR，原子完成）；普通券走 `COUNT(*)` 判上限（不限库存）。
- `@Idempotent`（3 秒窗口）挡双击，数据库唯一索引兜底「一人一张」档位。
- 领取资格 `coupon:got` 用 Hash（field=userId）存储，重复预热只刷库存不删资格记录。

### 6. 打卡与排行榜（point）

- **Bitmap 打卡**：一人一 key（`point:check:{userId}`），offset = 距基准日（2026-01-01 常量）的天数，可跨月数连续天数；key 丢失时从 DB 明细自动重建最近 400 天。
- **判重用唯一索引而不是「先 GETBIT 再 SETBIT」**：两条 Redis 命令之间存在窗口，并发双击会加两次分；`t_checkin_record` 的 `uk_user_date` 才是最终防线。
- **写入顺序**：不可重建的先落库（打卡明细、积分流水、余额同事务），可重建的最后写缓存（ZINCRBY 月榜）——榜少一分能回填，账错不得。
- **月度排行榜**：ZSet 实时榜 Top50 + 50 名外补真实名次；昵称由 Feign 一次批量拉取，失败降级「神秘运动者」。
- **赛季归档**：每月 1 日 00:10 读上月 ZSet → 补打卡数与最长连续 → `INSERT ... ON DUPLICATE KEY UPDATE` upsert 到 `t_rank_archive`（主键 `(season, user_id)`，重跑安全）→ 旧 key 设 40 天 TTL；名次用归档时固化的 `rank_no`，结榜那一刻是历史事实，不重算。

### 7. 社区（community）

- **三档可见性**：PUBLIC / FRIEND / PRIVATE 分开判定，不写成一个 OR 查询（OR+IN 用不上索引）；**只有 PUBLIC 才扇出**到粉丝收件箱——FRIEND 一旦进了收件箱等于公开且不可逆。
- **推拉结合 Feed**：
  - 推荐 tab：热度分 `LOG10(收藏×5 + 评论×3 + 点赞×2 + 浏览×0.2 + 1) / (距今小时数 + 2)^1.5`，权重按用户付出成本排序，`LOG10` 压量级、`+2` 新帖保护、`1.5` 衰减重力；时间取整小时形成「小时桶」，同小时内分数不变，**翻页不重复**；推荐池只框近 7 天，避免全表算分。
  - 关注 tab：推模式收件箱（ZSet，score=发布时间，trim 最近 1000 条）只当**候选 id 集合**，翻页交给 DB 的 `id < 游标`——不在 ZSet 上按下标翻页（新动态会把成员往后挤，第 2 页必重复）。
  - 好友 tab：拉模式，「互关好友」用自连接 SQL 现算，不建 `t_friend` 表（建了就要在关注/取关时双向同步，同步失败即数据不一致）。
- **两级评论**：`root_id` 平铺存储，两条 SQL 查完整页（一级分页 + `root_id IN(...)` 捞回复），不在循环里逐条查；`replyToUser` 由服务端从父评论行取，不接受前端传值（防伪造「回复 @教练」）；软删评论留占位，不把整串回复带走。
- **浏览量三层记账**：Kafka 明细（复用埋点 topic）+ Redis Hash 存未入账增量 + MySQL `view_count` 账本；定时任务用 `HINCRBY field -delta` **原子取出**增量再刷库（GET→写库→DEL 会把中间新产生的浏览 DEL 掉，永久少计）；展示值 = 账本 + 增量，即时 +1；去重按「一人一帖一天」（当日 Set + 落库唯一键兜底并发）。

### 8. 行为埋点闭环（behavior，Kafka）

```text
前端 SDK 攒批（满 5 条或 3 秒）→ POST /behavior/report（允许匿名，deviceId 区分）
  → 逐条投 Kafka（topic yd-behavior-event，key=deviceId：同设备进同一分区、按序消费）
  → 消费者：eventId 三层幂等（SETNX → uk_event 唯一索引 → 异常仅记日志不阻断）
  → 落 t_behavior_log 明细；course_click 事件额外 ZINCRBY 课程热度榜
  → 首页调 /behavior/hot/courses 取 TopN 按热度重排推荐位
```

埋点 → 流式聚合 → 反哺业务展示，形成完整数据闭环；上报接口不落库、保持极快。

### 9. 身体数据（user，MySQL + MongoDB）

- **一服务两存储**：健身档案（字段有界、要跨人统计）放 MySQL `t_user_profile`（主键即 user_id，物理上不可能重复建档）；每日身体记录（字段可缺、永远只按人取一段）放 MongoDB `yd_profile.body_record`。BMI 算出来返回、不落库（身高体重一变它就错，能算的不要存）。
- **一人一天一篇**：写入 `upsert(query={userId, recordDate})`，撞 `uk_user_date` 唯一索引降级为 update 重试一次。
- **缺失与零值严格区分**：「没测」的字段用 `Update.unset()` 抹掉，不存 0 也不存 null——体脂 0% 和「今天没测体脂」是两件事。
- **趋势五段聚合管道**：`match(userId) → match(日期区间) → match(metric exists) → project → sort+limit`，第三段专门剔除缺测日期，否则折线会连出不存在的直线。

### 10. 管理端 BFF 与文件服务（admin-server / file）

- **登录**：图形验证码（答案 Redis 存 2 分钟、取出即删防重放）→ BCrypt 比对 → 签发管理端 JWT（独立密钥，与用户端不通用）。
- **RBAC**：`@RequireRole("SUPER")` + 拦截器，SUPER / OPERATOR 两角色，粒度到菜单级。
- **操作日志**：`@OperateLog` 切面记录参数（JSON 截断脱敏）、IP、耗时，独立线程池异步落库，队列满丢弃——日志坏了不能拖垮主流程。
- **跨服务聚合统一出口**：会员昵称补全统一走 `MemberNameLoader`（一次 Feign + 失败降级空 Map），三处页面共用同一降级策略；看板三路取数每路单独降级（失败给 0 不白屏）。
- **跨库前置校验由 BFF 编排**：删除教练先问 order「是否还有未开始的预约」，通过后再让 course 删——不让 course 反向依赖 order，服务依赖不成环。
- **图片上传四道校验**：后缀白名单 → 文件头魔数校验 → UUID 重命名（不可猜）→ 路径 normalize 后判 `startsWith(root)` 防路径穿越；库内存相对路径，换域名 / 上 CDN 不用刷表。

### 11. 横切能力与并发防护

**并发防护三档**（按「错了的代价」分级，不为练习项目引入分布式事务 / TCC / RedLock）：

| 档位 | 判据 | 手段 | 覆盖点 |
|---|---|---|---|
| 必须防 | 错了就是资损或数据打架 | 唯一索引 + 带状态条件的原子 UPDATE（CAS），同一本地事务 | 秒杀扣库存（Lua）、抢时段 `uk_coach_start`、支付 / 取消 / 扣次、领券、兑换扣积分、打卡判重、点赞收藏、身体记录 upsert |
| 值得防 | 双击 / 重试造成重复副作用 | `@Idempotent` 时间窗（Redis SETNX）+ 唯一索引兜底 | 领券 3s、创建预约 5s、评价 5s |
| 值得防·变体 | 同一份意向只能兑现一次 | 一次性令牌：GETDEL 原子消费，用掉即废 | 下单消费 preview 预发的 orderNo |
| 不防 | 低频管理动作，错了重做 | 直接写，靠唯一索引自然拦 | 配课、门店、启停教练、图片上传 |

**双 MQ 分工**：业务指令类消息（要路由 / 延迟 / 死信 / 确认）走 RabbitMQ——秒杀建单、延迟取消、积分事件、站内通知；高吞吐追加写（按 key 保序、可重放）走 Kafka——行为埋点流。

**消费幂等三层**：`eventId` SETNX（快）→ 数据库唯一索引（稳）→ 异常仅记日志不阻断消费组。

**降级原则**：跨服务读全部有兜底（评分 →「暂无评分」、昵称 →「神秘运动者」、看板 → 该格 0），旁路失败不挡主流程；**写操作绝不吞异常**。

**统一约定**：响应信封 `{code, message, data}`；错误码五段不复用号位；业务失败只抛 `BusinessException` 转 Result、不返回 HTTP 500；Feign 契约放 `*-api` 模块，改错编译期即报。

## 定时任务与消息消费者

| 任务 / 消费者 | 触发 | 职责 | 为什么不需要分布式锁 |
|---|---|---|---|
| 秒杀活动扫描 | 每 30 秒 | 到点预热库存到 Redis、到点结束活动 | 状态翻转 `WHERE status=0` CAS，多实例只有一个改得动 |
| ReservationSettleTask | 每分钟 | 下课的预约置 FINISHED + 扣凭证一次 | `WHERE status='BOOKED' AND end_time<=NOW()`，重跑影响 0 行 |
| EntitlementExpireTask | 每小时 | 过期凭证置 EXPIRED；一次没用过的推订单退款 | 全程带状态条件，CAS 重跑安全 |
| RankArchiveTask | 每月 1 日 00:10 | 上月榜归档 + 旧 key 设 TTL | upsert + 复合主键，重跑安全 |
| PostViewFlushTask | 每 30 秒 fixedDelay | 浏览增量原子刷入 view_count | fixedDelay 天然串行 |
| BookingTimeoutConsumer | RabbitMQ | 15 分钟超时取消 | 状态 CAS；失败 `requeue=false` 进 DLX |
| SeckillCreateConsumer | RabbitMQ | 秒杀异步建单 + DB 扣份数 | 幂等键 + 状态判断；失败回补 Redis |
| CourseFinishedConsumer | RabbitMQ | 完课 +30 积分 | 业务键 `userId:videoId` 两层幂等 |
| NotifyEventConsumer | RabbitMQ | 事件 → 站内消息（topic 交换机按 notify.* 分发） | 通知重复一次不影响业务 |
| BehaviorEventConsumer | Kafka | 埋点落明细 + 热度榜 ZINCRBY | eventId SETNX + `uk_event` 唯一索引 |

## 数据库设计

**每服务独立库**（8 库 39 表 + MongoDB 1 集合）：

| 库 | 表数 | 核心表 |
|---|---|---|
| yd_user | 3 | t_user、t_user_profile（健身档案）、t_message（站内消息） |
| yd_course | 14 | t_course、t_course_store（课 × 店价格与时间窗）、t_coach / t_coach_course、t_store、t_seckill_activity、t_video / t_video_progress、t_coupon / t_user_coupon / t_coupon_course / t_coupon_store、t_course_comment、t_banner |
| yd_order | 8 | t_booking（订单）、t_entitlement（凭证）、t_reservation（预约）、t_coach_busy（教练占用格）、t_rating + 三张评分统计表 |
| yd_point | 5 | t_member_points、t_points_log、t_checkin_record、t_rank_archive、积分商城表 |
| yd_community | 5 | t_post、t_follow、t_post_like、t_post_collect、t_post_comment |
| yd_behavior | 1 | t_behavior_log（props 为 JSON 列，只写不读） |
| yd_admin | 2 | t_admin（BCrypt 散列）、t_operate_log |
| yd_file | 1 | t_file（相对路径元数据） |
| Mongo yd_profile | 1 集合 | body_record（`uk_user_date` 复合唯一索引，一人一天一篇） |

- 建表脚本在 `sql/01~08_yd_*.sql`（MySQL 按文件名序自动导入），增量变更在 `sql/upgrade/`。
- 索引要点：同人同课 / 一人一单 / 一天一打卡全部靠唯一索引兜底；订单按 `(user_id, status)`、`(course_id, user_id, status)` 建索引。


## 目录结构

```text
.
├── fitness-common/          统一响应 / 错误码 / 全局异常 / JWT / UserContext / @Idempotent
├── fitness-gateway/         网关：路由 + JWT/jti 校验 + 用户上下文透传
├── fitness-user-api/ …      服务间 Feign 契约 ×4（接口 + DTO）
├── fitness-user/            用户：账号 / 资料 / 消息中心 / 身体数据（MySQL + Mongo）
├── fitness-course/          课程：缓存三件套 / 门店 GEO / 视频 / 秒杀活动 / 优惠券
├── fitness-order/           订单：试算 / 建单 / 支付出票 / 预约时段 / 评价 / 秒杀
├── fitness-point/           积分：Bitmap 打卡 / 流水 / 兑换 / ZSet 月榜与归档
├── fitness-community/       社区：Feed 推拉结合 / 两级评论 / 浏览量三层记账
├── fitness-behavior/        埋点：Kafka 接收与消费 / 热度榜
├── fitness-admin-server/    管理端 BFF：RBAC / 操作日志 / 跨服务聚合
├── fitness-file/            文件：图片上传四道校验 + 静态读取
├── sql/                     建表脚本（01~08）与 upgrade 增量
├── docker/                  docker-compose（中间件 + Nginx）、.env 模板、联调种子脚本
├── docs/                    架构设计与数据库设计文档
└── pom.xml                  父 POM：依赖仲裁与插件配置
```

