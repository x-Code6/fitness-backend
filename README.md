# 悦动健身 · 后端（fitness-backend）

微服务后端，架构设计见 `../docs/后端架构设计.md`。本 README 只讲"怎么跑"和"目录怎么看"。

## 模块总览

| 模块 | 端口 | 职责 | 状态 |
|---|---|---|---|
| fitness-common | — | 统一响应/错误码/全局异常/JWT 工具/用户上下文 | ✅ 已完成 |
| fitness-gateway | 8080 | 路由 + JWT 全局校验 + X-User-Id 透传 | ✅ 已完成 |
| fitness-user | 8101 | 注册/账号密码登录/资料（验证码登录预留） | ✅ 已完成 |
| fitness-course | 8102 | 课程/缓存三件套/门店 GEO/视频/秒杀活动 | 🔜 第②步 |
| fitness-order | 8103 | 预约/状态机/延迟取消/秒杀 Lua+MQ | 🔜 第③④步 |
| fitness-point | 8104 | Bitmap 打卡/积分/ZSet 排行榜 | 🔜 第⑤步 |
| fitness-behavior | 8106 | 埋点上报 → Kafka → 热门课程榜 | 🔜 第⑥步 |
| fitness-community | 8105 | Feed 流/点赞/关注 | ✅ 已完成 |
| fitness-file | 8108 | 图片上传 + /uploads 静态读取 + t_file 元数据 | ✅ 已完成 |

## 环境要求

- JDK 17+（本机 21 可编译运行）、Maven 3.9+
- 中间件（跑在 CentOS VM 的 Docker）：MySQL 8、Redis 7、RabbitMQ 3.13（延迟插件）、Nacos 2.4
- 初始化数据库：`sql/` 目录下按服务名执行（如 `mysql -uroot -p < sql/yd_user.sql`）

## 配置约定（全部支持环境变量覆盖）

| 变量 | 默认值 | 说明 |
|---|---|---|
| NACOS_ADDR | 127.0.0.1:8848 | Nacos 地址（VM 部署时填 VM IP） |
| DB_HOST / DB_PORT / DB_USER / DB_PASS | 127.0.0.1 / 3306 / root / 123456 | MySQL |
| REDIS_HOST / REDIS_PORT | 127.0.0.1 / 6379 | Redis |
| MQ_HOST / MQ_PORT / MQ_USER / MQ_PASS | 127.0.0.1 / 5672 / guest / guest | RabbitMQ |
| KAFKA_ADDR | 127.0.0.1:9092 | Kafka |
| JWT_SECRET | （默认值） | 必须网关与用户服务一致 |

## 本地跑通用户服务（最小验证）

```bash
# 1. 建库（本机或 VM 的 MySQL 执行）
mysql -uroot -p < sql/yd_user.sql

# 2. 启动（演示账号：13800008819 / 123456）
cd fitness-user
mvn spring-boot:run -Dspring-boot.run.arguments=--spring.cloud.nacos.discovery.enabled=false

# 3. 验证（不经网关，直连 8101）
curl -X POST http://localhost:8101/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800008819","password":"123456"}'
```

完整链路（前端 → 网关 → 服务）需要 Nacos 在线后启动 gateway 一并验证。

## 编码风格约定

- 三层架构：controller（参数+编排）→ service（业务逻辑）→ mapper（数据访问）
- 方法体长流程用 `// ===== 第 N 步：xxx =====` 分步注释，每个方法有 Javadoc
- 实体用 MyBatis-Plus 注解（@TableName/@TableId/@TableField），VO 与实体严格分离
- 演示账号/默认密码只允许出现在开发配置与 SQL 注释里，生产用环境变量
