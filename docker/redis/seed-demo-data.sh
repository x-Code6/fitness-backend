#!/usr/bin/env bash
# ============================================================================
# 悦动健身 · Redis 联调种子数据（测试环境，在 VM 上跑）
#
# 用法：
#   cd ~/fitness/docker && bash redis/seed-demo-data.sh
#
# ★为什么 MySQL 灌了种子还要这份脚本：
#   页面不是全都读 MySQL。下面这几处的展示数据源在 Redis，库里有多少行都没用：
#     1) 月度排行榜页   → ZSet rank:month:{yyyyMM}（打卡/完课时 ZINCRBY 才会长出来）
#     2) 首页热门推荐   → ZSet behavior:hot:course:{yyyyMM}（埋点消费时 ZINCRBY）
#     3) 社区"关注"Tab  → ZSet feed:inbox:{userId}（发动态时写给粉丝的收件箱）
#     4) 秒杀专享券领取 → String coupon:stock:{couponId}（Lua 的账本）
#   第 4 项最容易踩：种子里 1、3 号券是 SECKILL 类型，走 Redis 领券；
#   而 coupon:stock 只在"管理端保存秒杀券"时预热。SQL 直插的券没人预热，
#   Lua 取不到键就按"已抢完"处理（宁可不发也不能凭空发），
#   表现是领券中心那张券点了永远报"已抢完"——不是 bug，是缺账本。
#
# ★不需要灌的三样（都有代码兜底，别多此一举）：
#   - store:geo         StoreServiceImpl 实现 ApplicationRunner，course 启动时全量重灌
#   - seckill:stock     秒杀活动由 30 秒扫描任务到点预热（种子活动①②就是为演示它写的）
#   - point:check:{id}  打卡位图可由 t_checkin_record 重建（CheckinServiceImpl#ensureBitmap）
#   - course:detail     Cache-Aside，首次读自动回填
#
# ★幂等：ZAdd/HSet 重复执行只是覆盖同样的 member→score，跑两次不会翻倍。
# ============================================================================
set -euo pipefail

R() { docker exec -i redis redis-cli "$@"; }

# yyyyMM：和后端 DateTimeFormatter.ofPattern("yyyyMM") 同口径。
# 用 date 而不是写死 202610，跨月之后脚本还能接着用。
YM="$(date +%Y%m)"

echo "== 灌 Redis 演示数据（月份键 $YM）=="

# ---------------------------------------------------------------------------
# 1) 月度排行榜 ZSet：score 必须等于 yd_point.t_member_points.balance，
#    否则"我的积分"和榜上数字对不上，看起来像算错了。
#    10001 = 320（种子余额）；10002 种子表里没有它的行，
#    要让它也在榜且两边一致，先补一句 MySQL：
#      docker exec -i mysql mysql -uroot -p123456 -e \
#        "INSERT INTO yd_point.t_member_points(user_id,balance,total_checkin,continuous_days)
#         VALUES(10002,210,5,2) ON DUPLICATE KEY UPDATE balance=210;"
# ---------------------------------------------------------------------------
# ★★ ZADD 的参数顺序是 <b>score 在前、member 在后</b>，和直觉相反，写反了不会报错，
#    只会安静地建出一个"member 是分数、score 是用户 id"的榜——本页第一版就踩了这条，
#    实际建出来的是 member=320/210、score=10001/10002，排行榜页显示的全是不存在的用户。
#    每次改这块都用文末的 ZRANGE WITHSCORES 复核一次，别看命令推理。
R ZADD "rank:month:${YM}" 320 10001
R ZADD "rank:month:${YM}" 210 10002
# 月底自然作废，给 40 天足够覆盖联调周期（后端真实写入时也是靠月键区隔，不靠 TTL）
R EXPIRE "rank:month:${YM}" 3456000

# ---------------------------------------------------------------------------
# 2) 首页热门课程榜：member=课程 id，score=点击数（★同样是 score 在前）。
#    故意让 4 号课最高、1 号课最低，方便一眼看出首页推荐真按热度重排了
#    （种子课程见 sql/02_yd_course.sql：1=燃脂跳操 2=杠铃塑形 4=动感单车 …，
#    课程 id 只到 13，别写 17/26 这种不存在的 id——榜单拿 id 回查课程，查不到就少一条）。
# ---------------------------------------------------------------------------
R ZADD "behavior:hot:course:${YM}" 26 4 17 2 9 9 4 1
R EXPIRE "behavior:hot:course:${YM}" 3456000

# ---------------------------------------------------------------------------
# 3) 社区"关注"Tab 的收件箱：10001 关注了 10002（sql/07_yd_community.sql t_follow），
#    10002 发的动态是 id=2 → member=2，score=发布时间毫秒（ZSet 按分数倒序取，
#    所以这个数只要求相对有序，用"当前时间-2小时"即可）。
#    ★member 是postId不是拼串，score 用毫秒不是秒——后端 reverseRange 按毫秒算的。
# ---------------------------------------------------------------------------
NOW_MS=$(( $(date +%s) * 1000 - 7200000 ))
R ZADD feed:inbox:10001 "${NOW_MS}" 2
# 10002 关注了 10001；10001 发的动态是 1、3 两条
R ZADD feed:inbox:10002 "$(( NOW_MS + 3600000 ))" 1
R ZADD feed:inbox:10002 "${NOW_MS}" 3

# ---------------------------------------------------------------------------
# 4) 秒杀专享券的库存账本（对应 sql/02_yd_course.sql 的券 1、3）：
#    券 1 remain=100 限领 2 张，领取截止 NOW()+30 天；
#    券 3 remain=50  限领 1 张，领取截止 NOW()+15 天。
#    TTL 与"领取截止"对齐，和后端 preheat 的算法一致（至少留 60 秒）。
#    ★coupon:got 那个 Hash 不要预置：它是"这人领了几张"的账，
#      一灌就等于替他领过了，演示"限领 N 张"反而看不出来。
# ---------------------------------------------------------------------------
R SET coupon:stock:1 100
R EXPIRE coupon:stock:1 2592000
R SET coupon:stock:3 50
R EXPIRE coupon:stock:3 1296000

echo
echo "== 自检（四个键都该有值）=="
R EXISTS "rank:month:${YM}" "behavior:hot:course:${YM}" coupon:stock:1 coupon:stock:3
echo "排行榜：" && R ZREVRANGE "rank:month:${YM}" 0 -1 WITHSCORES
echo "热门榜：" && R ZREVRANGE "behavior:hot:course:${YM}" 0 -1 WITHSCORES
echo "关注收件箱(10001)：" && R ZREVRANGE feed:inbox:10001 0 -1
echo
echo "验证接口（走网关，白名单游客接口不用登录）："
echo "  curl http://localhost:8080/course/list   # 首页课程"
echo "  登录拿 token 后："
echo "  curl -H \"Authorization: Bearer <token>\" http://localhost:8080/rank/monthly"
echo "  curl -H \"Authorization: Bearer <token>\" http://localhost:8080/community/feed?tab=follow"
