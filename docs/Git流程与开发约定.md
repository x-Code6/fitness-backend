# 悦动健身 · Git 流程与开发约定

> 适用范围：本 monorepo（`docs` + `fitness-backend` + `fitness-web` + `fitness-admin`）。
> 这份文件是**给自己定的纪律**，不是团队规范文档。所有规则的取舍理由都基于"一个人 + AI 协作、要能讲清项目"这个前提。

## 1. 仓库结构

单仓（monorepo），一个仓库同时管后端 13 个模块、两端前端、文档和 SQL。

理由：这次重构是跨端的（例如"删掉 `t_course_schedule`"要同时改 course/order 两个服务的接口、管理端场次弹窗、H5 时间选择器）。分三仓意味着一个功能要开三个同名分支、合三次、还要人工对齐合并顺序。单仓能让"一个功能 = 一个分支 = 一次合入"成立。

原 `fitness-backend` 独立仓库的那一个提交，已备份在 `.backup/yd-backend-history.bundle`（不入库），需要时用 `git bundle unbundle` 取回。

## 2. 分支模型：主干 + 短功能分支

```
main ────────────────────────────────► 永远可编译、可运行、可演示
   │
   ├─ feat/entitlement            ────┘（合回 main，--no-ff）
   ├─ fix/gateway-whitelist-match ────┘
   └─ chore/gitignore             ────┘
tag: v0-baseline → v1-fixes → v2-booking-core → v3-cart-order → v4-rating → v5-misc
```

- **`main` 不接受直接提交**。所有改动走功能分支。这条对我（AI）同样生效：每次动手前先建分支，改完合回。
- **不采用 GitFlow**（develop / release / hotfix 三层）。它的价值在多人并行和版本火车，一个人做只会多一层无意义的合并。**但"分支按功能划"这条必须守**，收益是：改坏了只废一条分支、review 时一眼看出这条分支干了什么、以及逼自己在开工前想清楚功能边界。
- 分支寿命 **1~3 天**。超过 3 天说明这个功能该拆了。
- 合并用 `git merge --no-ff <branch>`，保留"这个功能是一组提交"的形状（将来能指着 log 讲"预约重构是我做的这几步"）。

### 前缀约定

| 前缀 | 用途 | 例 |
|---|---|---|
| `feat/` | 新功能 | `feat/cart-checkout` |
| `fix/` | 修 bug（含第三部分问题清单里的条目） | `fix/video-finished-event-idempotent` |
| `refactor/` | 不改行为的结构调整 | `refactor/drop-course-schedule` |
| `chore/` | 构建、忽略规则、依赖、脚本 | `chore/gitignore-and-baseline` |
| `docs/` | 只改文档 | `docs/api-contract-table` |
| `test/` | 只补测试 | `test/seckill-lua-branch` |

分支名一律**小写连字符、动宾结构**，不写 `feat/test1`、`feat/修改2` 这种。

## 3. 提交规范（Conventional Commits）

标题：`类型(范围): 一句话说清做了什么`，50 字内，中文正文可以。

正文必填**为什么**，不填"改了什么"（那些 `git diff` 自己会说）：

```
fix(gateway): 白名单匹配从 startsWith 改为 AntPathMatcher

原因：/api/admin/** 与 /**/v3/api-docs 是 glob 语法，用 String::startsWith
永不匹配，导致管理端登录接口也被要求用户侧 JWT，后台 100% 进不去。
同时把拒绝响应的状态码按 ErrorCode 映射（/inner 与无权限返 403，
鉴权失败才返 401），此前 deny() 对所有拒绝都硬编码 401。

涉及：AuthGlobalFilter.java, application.yml
```

- **一个提交只干一件事**。功能代码 + 它的 SQL 迁移 + 它的前端调用可以同属一个提交（不可分割）；顺手格式化、改注释要单独 `chore` 提交，否则 diff 里找不到真改动。
- **表结构变更和代码在同一个提交**，且额外留一份回滚脚本（`DROP` 的反向 `ALTER`）。企业里叫 migration with down script，手写两份也算。
- 提交前跑一次编译：后端 `mvn -q -DskipTests package`，前端 `npm run build`。**编译不过的分支不合入 main**——这是"按功能分支"能成立的前提，否则分支只是个名字。

## 4. 红线（这些会直接造成损失）

1. 不在 `main` 上直接改；不让 AI 在 `main` 上直接改。
2. 不提交 `node_modules/`、`target/`、`dist/`、`.mimosa/`、`.backup/`（已在 `.gitignore`）。
3. **`git add .` 之后必须 `git status` 看一遍再 commit**，确认没有多出该入库外的东西。
4. 真实口令/密钥不进库：`application.yml` 保持 `${VAR:默认值}` 形式，默认值用占位串（现在是 `123456`、`guest`、`...please-change-me-32bytes`，**本地仓库可接受，但将来推远端前必须先按 P2-4 换成无默认值的必填环境变量**）。
5. 不用 `git push -f` 到任何有他人的分支；不 `--no-verify` 跳钩子。
6. 不改 git 全局配置（`user.name` 等已配好）。

## 5. 分期分支清单

对应 `docs/数据库设计与功能实现说明.md` 4.10。**每条分支 = 一个能单独说清楚的功能。**

| 期 | 分支 | 完成标志 |
|---|---|---|
| **v0 基线** | `chore/gitignore-and-baseline` ✅ 已完成 | 打了 `v0-baseline` |
| **第0期 联调地基** | `fix/gateway-whitelist-match`<br>`fix/gateway-add-routes`<br>`fix/admin-api-path-align`<br>`chore/switch-off-mock` | `USE_MOCK=false`，H5 与后台都能连真实后端 |
| **第1期 正确性** | `fix/video-finished-event-idempotent`<br>`feat/checkin-record-dedup`<br>`fix/rank-score-consistency`<br>`feat/idempotent-aop`<br>`fix/timeout-dlx-poison-message`<br>`feat/jwt-token-allowlist`<br>`fix/cache-physical-ttl` | 第三部分 P0 全绿 |
| **第2期 主战役** | `feat/coach-domain`<br>`feat/course-store-window`<br>`refactor/drop-course-schedule`<br>`feat/entitlement`<br>`feat/reservation-slot`<br>`feat/reservation-settle-task` | 买课 → 约教练 → 上课 → 自动扣次 全流程通 |
| **第3期 电商骨架** | `feat/cart-checkout`<br>`feat/order-list-detail`<br>`feat/mine-summary`<br>`feat/expire-refund-task` | 订单页与"我的"页可用 |
| **第4期 评价** | `feat/rating-bayes`<br>`feat/content-course-comment` | 一单一评、软删不可重评、展示课评论可回复 |
| **第5期 独立能力** | `feat/coupon-types`<br>`feat/rank-archive`<br>`feat/sentinel-gateway`<br>`fix/behavior-kafka-manual-ack` | 券两类表单联动、历史月榜可查、限流可视化 |

顺序上的硬依赖：**第 0 期必须先做完**。理由写在主文档 4.10——长期只在 Mock 里自测（`startsWith` 匹配让前端自造路径永远能用）正是管理端 20 多处路径漂移能攒下来的根因；跳过它，新问题会一次性爆在最后一刻。

## 6. 常用命令备忘

```bash
git switch -c feat/entitlement          # 开分支（别用 checkout -b，switch 更明确）
git -c core.quotepath=false status      # 中文路径不转义，看得清（本仓库有大量中文文件名）
git diff --cached --name-only           # 提交前扫一眼暂存了什么
git log --oneline --graph --decorate    # 看功能分支形状
git tag -a v1-fixes -m "第1期：P0 全部修复"   # 每期收尾
git diff v0-baseline..v1-fixes --stat   # 一期到底改了多少文件
git switch main && git merge --no-ff feat/entitlement
git branch -d feat/entitlement          # 合完就删，分支列表要干净
git reset --hard v0-baseline            # ⚠️ 只在功能分支上用于放弃改动，main 上禁止
```

## 7. 与 AI 协作的专门约定

这几条是把 git 当"围栏"用，比依赖记忆可靠得多：

1. **开工前先 `git status` 确认干净**，不干净先处理（可能是上一轮遗留，也可能是你自己的改动，别覆盖）。
2. **每个功能一条分支，AI 只在功能分支上改**。改坏了 `git switch main` 一秒回到干净状态，不用靠谁记住"上次改了哪几个文件"。
3. **AI 的提交必须能被质疑**：每个提交有正文说明"为什么"，评审时读正文而不是读 diff。
4. **每合并一期就打上 tag**，`git diff v2-booking-core..HEAD` 能直接看出下一期改了啥。
5. 大改动（例如删场次）拆成 2~3 个提交：先加新结构、再切调用方、最后删旧结构。**不要一个提交里"又加又删"**，那种 diff 人读不动，也没法单独回滚其中一半。
