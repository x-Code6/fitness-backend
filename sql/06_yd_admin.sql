-- ============================================================
-- 悦动健身 v2 · 管理域 yd_admin
-- 归属服务：fitness-admin-server (8107)，定位是 BFF（Backend For Frontend，
-- 为管理端前端专门包的一层）：它自己只存管理员与操作日志两张表，
-- 业务数据一律 Feign 透传回各业务服务的 /inner/** 接口，不复制业务逻辑。
-- ============================================================

CREATE DATABASE IF NOT EXISTS yd_admin
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE yd_admin;

-- ----------------------------------------------------------------
-- 管理员表（RBAC 的角色就存这一列）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_admin (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(20)  NOT NULL COMMENT '登录名',
    password   VARCHAR(100) NOT NULL COMMENT 'BCrypt 散列。★不在这里预置散列串：由 admin-server 启动时的初始化器用真实的 BCryptPasswordEncoder 生成（原因见 01_yd_user.sql 末尾）',
    real_name  VARCHAR(20)  NOT NULL COMMENT '姓名，日志与右上角展示用',
    role       VARCHAR(10)  NOT NULL DEFAULT 'OPERATOR' COMMENT 'SUPER 超管 / OPERATOR 运营。校验落在 AdminAuthInterceptor + @RequireRole 注解上（方法注解优先于类注解，SUPER 无条件放行）',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 / 0 禁用。★禁用要立即生效，必须配合 Redis 令牌白名单（删掉该管理员的 jti），否则已签发的 token 在整个有效期内仍然可用',
    last_login_at DATETIME  NULL COMMENT '最后登录时间',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '管理员表';

-- ----------------------------------------------------------------
-- 操作日志表（由 OperateLogAspect 这个 AOP 切面自动写，全项目唯一的切面）
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_operate_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    admin_id    BIGINT       NOT NULL COMMENT '操作人 id（快照：管理员改名或删号后，日志仍要能读出发起人）',
    admin_name  VARCHAR(20)  NOT NULL COMMENT '操作人姓名快照',
    module      VARCHAR(20)  NOT NULL COMMENT '业务模块，如"课程管理""核销"',
    operation   VARCHAR(50)  NOT NULL COMMENT '操作描述，如"新增课程"',
    method      VARCHAR(200) NULL COMMENT '请求方法（类名+方法名），排查用',
    params      VARCHAR(500) NULL COMMENT '参数 JSON，★截断到 500 防单行过大；写日志前必须脱敏（不能把 password 字段原样落进去）',
    ip          VARCHAR(40)  NULL COMMENT '操作 IP，支持 IPv6 长度',
    duration_ms BIGINT       NOT NULL DEFAULT 0 COMMENT '本次请求耗时（毫秒）',
    success     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 成功 / 0 失败（失败也要留痕）',
    error_msg   VARCHAR(200) NULL COMMENT '★v2 新增：失败时的异常摘要。旧表只有 success 位，日志显示"失败了"却说不出为什么',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_admin (admin_id, created_at),
    KEY idx_module (module, created_at)
) ENGINE = InnoDB COMMENT '管理端操作日志';

-- ============================================================
-- 种子数据
-- ============================================================
-- 管理员账号不在这里预置（散列同用户账号的理由）。初始化器要按下面固定 id 插入，
-- 因为 t_operate_log 的历史示例引用了它们：
--   id=1  admin    / 123456  → SUPER
--   id=2  operator / 123456  → OPERATOR（按当前角色配置：无会员、门店、日志权限）
--
-- ★v1 已知缺陷（v2 第 2 期要修）：OperateLogAspect 把写日志丢到独立线程池执行，
--   而 AdminContext 是普通 ThreadLocal —— 换线程后读不到，导致 admin_id 恒为 0、
--   admin_name 恒为"未知"。修法是在切面进入时（还在请求线程里）就把 adminId/adminName
--   读进局部变量，再带进异步任务，而不是在异步任务里读 ThreadLocal。
--   所以下面这 3 条示例日志是按"修好之后"的正确形态给的，真实运行日志会从这里续上。
INSERT INTO t_operate_log (admin_id, admin_name, module, operation, method, params, ip, duration_ms, success, error_msg) VALUES
(1, '运营管理员', '课程管理', '新增课程', 'CourseAdminController.save', '{"name":"燃脂跳操·Zumba 热舞","lessonHours":1}', '192.168.150.1', 46, 1, NULL),
(1, '运营管理员', '教练管理', '配置可授课范围', 'CoachAdminController.assignCourse', '{"coachId":1,"courseIds":[1,2,6,8]}', '192.168.150.1', 21, 1, NULL),
(2, '内容运营',   '核销',    '执行核销', 'VerifyAdminController.doVerify', '{"verifyCode":"4829157306"}', '192.168.150.1', 33, 1, NULL);
