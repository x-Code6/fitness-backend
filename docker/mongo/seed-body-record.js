/* ============================================================================
 * 悦动健身 · MongoDB 联调种子数据
 * 集合：yd_profile.body_record（一个用户一天一条，见 BodyRecord.java）
 *
 * 用法（在 VM 上，文件先 scp 到 ~/fitness/docker/mongo/）：
 *   docker exec -i mongo mongosh --quiet yd_profile < mongo/seed-body-record.js
 *
 * 重复执行安全：全部走 updateOne(..., {upsert:true})，按唯一键
 * (userId, record_date) 覆盖同一天的那份，不会插出第二条。
 *
 * ★为什么这个数据必须由脚本灌、后端没有初始化器：
 *   演示账号是 DemoAccountInitializer 在启动时用 passwordEncoder 现算散列写进
 *   MySQL 的（散列必须与加密器同源），但身体数据没有"同源"问题，纯展示数据，
 *   放代码里初始化会让 user 服务每次启动都连一次 Mongo 写 30 条——
 *   而它本来就是"用户自己填"的表，一次性脚本更合适。
 *
 * ★为什么故意留几天缺字段：
 *   BodyRecord 的约定是"没填 = 文档里根本没有这个字段"，用 0 占位会在趋势图上
 *   画出假深渊。所以 10002 那一批大部分天只填体重，正好用来验证前端的
 *   "缺值断线/不画点"处理，而不是连成一条假线。
 *
 * ★updated_at 的时区：
 *   这里写的是"不带 Z 的本地时间串"，mongosh 按容器时区解析。compose 里 mongo
 *   配了 TZ=Asia/Shanghai，后端 JVM 也是 +8，两边一致所以存进去、读出来是同一个
 *   墙上时间。如果哪天把容器时区改成 UTC，这里会出现 8 小时偏移——表现是
 *   列表页"今天 21:30 更新过"变成"今天 13:30"。
 * ==========================================================================*/

const DB = db.getSiblingDB('yd_profile');

/* 与 @CompoundIndex(name="uk_user_date", unique=true) 对齐。
   后端 auto-index-creation=true 启动时也会建，这里先建是为了"脚本能脱离后端单独跑"。
   幂等：已存在时 createIndex 不报错、不重建。 */
DB.body_record.createIndex({ userId: 1, record_date: 1 }, { name: 'uk_user_date', unique: true });

/** 距今天 n 天前的 'yyyy-MM-dd'（用本地时间算，和后端 LocalDate.now() 同口径） */
function dayOffset(n) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

/** 某一天的"墙上时间"→ Date（不带 Z，按容器时区解析，见文件头说明） */
function at(n, hour, minute) {
  const d = new Date(`${dayOffset(n)}T${String(hour).padStart(2, '0')}:${String(minute ?? 0).padStart(2, '0')}:00`);
  return d;
}

/**
 * 写一条：null / undefined 的字段整个从文档里去掉（★不是写成 null）。
 * 这是 BodyRecord 注释里那条约定的落地方式，别让种子数据第一个破坏它。
 */
function put(userId, doc) {
  const clean = { userId };
  Object.keys(doc).forEach((k) => {
    if (doc[k] !== null && doc[k] !== undefined) clean[k] = doc[k];
  });
  DB.body_record.updateOne(
    { userId, record_date: doc.record_date },
    { $set: clean },
    { upsert: true },
  );
}

/* ---------------------------------------------------------------------------
 * 10001（小鱼同学，手机号 13800008819 / 口令 123456）
 * 30 天完整趋势：体重 78.6 → 74.2 缓降，体脂/腰围同步下降。
 * 数值是手算的确定性公式，每次跑结果一样（随机数会让"为什么图变了"变成日常）。
 * ------------------------------------------------------------------------- */
for (let i = 29; i >= 0; i--) {
  // dayIdx：29 天前 = 0，今天 = 29，趋势往后走
  const k = 29 - i;
  // 体重：整体线性下降 + 每 3 天一次小幅回升（模拟周末聚餐），保留 1 位小数
  const weight = (78.6 - k * 0.15 + (k % 4 === 3 ? 0.4 : 0)).toFixed(1);
  const bodyFat = (26.4 - k * 0.06).toFixed(1);

  put(10001, {
    record_date: dayOffset(i),
    weight: Number(weight),
    body_fat_rate: Number(bodyFat),
    waist_cm: Number((88.5 - k * 0.13).toFixed(1)),
    // 围度类不是每天量：只有整周（每 7 天）记一次，其余日子这个字段直接不存在
    chest_cm: k % 7 === 0 ? Number((98.2 + k * 0.03).toFixed(1)) : null,
    hip_cm: k % 7 === 0 ? Number((96.4 - k * 0.02).toFixed(1)) : null,
    sleep_hours: Number((6.5 + (k % 3) * 0.5).toFixed(1)),
    energy_level: (k % 5) + 1,           // 1~5 循环
    soreness_level: k % 3,               // 0~2；★0 是"不酸"这个合法值，不是"没填"
    note: k === 12 ? '昨天聚餐，今早体重涨了就记一下' : null,
    updated_at: at(i, 7, 20 + (k % 3)),  // 晨起称重的样子
  });
}

/* ---------------------------------------------------------------------------
 * 10002（爱运动的鹿，13800008820 / 123456）
 * 只记体重 + 少量几天，用来演示两个场景：
 *   ① 列表/趋势图对"字段缺失"的兜底；
 *   ② 多账号下按 userId 取数不串（换号登录看不到 10001 的数据）。
 * ------------------------------------------------------------------------- */
for (let i = 11; i >= 0; i--) {
  const k = 11 - i;
  put(10002, {
    record_date: dayOffset(i),
    weight: Number((61.2 - k * 0.05).toFixed(1)),
    sleep_hours: k % 2 === 0 ? Number((7 + (k % 2) * 0.5).toFixed(1)) : null,
    energy_level: k % 2 === 0 ? (k % 4) + 1 : null,
    updated_at: at(i, 21, 5),
  });
}

/* 执行结果自查：两个数对得上就说明灌进去了 */
const total = DB.body_record.countDocuments({});
const days = DB.body_record.distinct('record_date').length;
print(`body_record 现有文档 ${total} 条，覆盖 ${days} 天`);
print(`10001 最近一天：${JSON.stringify(DB.body_record.findOne({ userId: 10001 }, { _id: 0 }).record_date)}`);
print('验证命令：docker exec -it mongo mongosh --quiet yd_profile --eval "db.body_record.find({userId:10001}).sort({record_date:-1}).limit(3).forEach(d=>printjson(d))"');
