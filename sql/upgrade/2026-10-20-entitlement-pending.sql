-- ============================================================
-- 2026-10-20 · 给凭证补上"已预约未上课"这个占用维度
-- 归属库：yd_order（订单服务）
--
-- 【这次修的是什么】
-- 预约一节课之后，凭证的剩余次数不变（used_times 只在"下课"时才 +1，这是 D5 定的规则：
-- 取消不扣次、爽约才算消费）。结果就是从"约上"到"上完"这段时间里，凭证在系统看来仍然满额，
-- 一张三次的凭证可以同时把下周约满。
-- 修法不是往 t_entitlement 里再塞一个计数字段，而是把"占用"当作事实来读：
--   可用次数 = total_times - used_times - (这张凭证当前挂着几节 BOOKED 的预约)
-- 计数字段要和取消、超时结账、退款、过期、人工修数据每一处同步，漏一次就永久对不上、
-- 而且事后无法推断出正确值；派生值不会，因为它就是数据本身。
--
-- 【这个脚本做的事】
-- 加一个派生列 + 一条唯一索引，把"同一张凭证同时只能挂一节待上的课"变成数据库级约束。
-- 为什么用生成列：MySQL 没有部分唯一索引（不能只约束 status='BOOKED' 的行），
-- 但唯一索引允许多个 NULL —— 于是 BOOKED→1、其它→NULL，就能只约束"待上"的那些行。
-- ============================================================

USE yd_order;

-- ---------------------------------------------------------------------------
-- 第 1 步：先体检。必须返回 0 行，才能建唯一索引。
-- 有输出的话先把多余的预约取消掉（或人工改成 CANCELED），别直接建索引——
-- ALTER 会因为重复键失败，而且失败信息容易被误读成"索引已存在"。
-- ---------------------------------------------------------------------------
SELECT entitlement_id, COUNT(*) AS booked_rows
  FROM t_reservation
 WHERE status = 'BOOKED'
 GROUP BY entitlement_id
HAVING COUNT(*) > 1;

-- ---------------------------------------------------------------------------
-- 第 2 步：建列 + 建索引。
-- ★已经建过就跳过：MySQL 的 ALTER 不支持 IF NOT EXISTS（列与索引都不支持），
--   重复执行第 2 步会报 Duplicate column name / Duplicate key name，那是"已经做好了"，
--   不是失败。判断方法看第 3 步。
-- ---------------------------------------------------------------------------
ALTER TABLE t_reservation
    ADD COLUMN pending_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'BOOKED' THEN 1 ELSE NULL END) STORED
        COMMENT '派生列：1=这节课还没上（占着凭证的一次额度），NULL=已取消或已上完';

ALTER TABLE t_reservation
    ADD UNIQUE KEY uk_pending_entitlement (entitlement_id, pending_flag);

-- ---------------------------------------------------------------------------
-- 第 3 步：验证。期望看到 pending_flag 是 GENERATED 列、并且 uk_pending_entitlement 存在
-- ---------------------------------------------------------------------------
SELECT column_name, data_type, extra
  FROM information_schema.columns
 WHERE table_schema = 'yd_order' AND table_name = 't_reservation' AND column_name = 'pending_flag';

SELECT index_name, column_name, non_unique
  FROM information_schema.statistics
 WHERE table_schema = 'yd_order' AND table_name = 't_reservation' AND index_name = 'uk_pending_entitlement';

-- ---------------------------------------------------------------------------
-- 【和代码的对应关系，改代码时两边要一起想】
--   实体：Entitlement#bookableAt(moment, pendingCount) / #remainTimes(pendingCount) / #validCovers
--   查询：ReservationMapper#countPendingByEntitlements（凭证列表、时段页、我的页四出口共用）
--   写入：ReservationServiceImpl#insertReservationWithUniqueRetry 撞 uk_pending_entitlement
--         要报"这张凭证还有一节课没上完"，不能当成随机号撞车去重试
--   释放：取消预约不写任何计数——那一行不再是 BOOKED，占用自然消失（这就是派生方案的好处）
--   结账：settleDue 把 BOOKED 置 FINISHED 并 used_times +1，两个数一减一加，总账守恒
-- ---------------------------------------------------------------------------
