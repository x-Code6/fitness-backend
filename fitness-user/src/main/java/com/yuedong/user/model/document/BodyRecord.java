package com.yuedong.user.model.document;

import lombok.Data;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 每日身体记录（MongoDB 集合 {@code body_record}，库名 {@code yd_profile}）。
 *
 * <p>★<b>一个用户的一天 = 一个文档</b>，同一天再填就是更新那一份，不新增。
 * 这是手动录入场景下唯一正确的粒度：用户的心智模型是"今天的我"，
 * 不是"第 3 次测量"。同一天称两次（晨起/睡前）在这种模型下会被合成一条，
 * 是<b>有意接受的取舍</b>——手动记录的价值在趋势，不在精度。</p>
 *
 * <p>★<b>字段为 null 时不要写进文档</b>（保存前会把 null 字段整个去掉）。
 * 这不是洁癖：体脂率 0、睡眠 0 在图上都是<b>合法的数值</b>，
 * 用 0 占位等于在趋势线上画出一个假深渊，用户会以为数据错了。
 * "没填"在 Mongo 里就是没有这个字段，读出来天然是 null，两种状态不会混。</p>
 *
 * <p>★为什么这条数据值得放 Mongo、账号订单却不放：见 {@code fitness-user/pom.xml}
 * 里那段依赖注释，一句话是<b>"指标可缺省 + 永远按人取一段 + 不参与业务判定"三条全中</b>。</p>
 */
@Data
@Document(collection = "body_record")
/*
 * ★唯一索引 = "一天一条"这件事的最终保证。
 * 光靠"先查再插"拦不住并发：两个请求同时查到"今天还没有"，就会插入两条。
 * 有了这条索引，第二个请求抛 DuplicateKeyException，服务层把它翻译成"更新今天那份"，
 * 和项目里所有 CAS 防护是同一个思路——<b>让数据库兜底，不靠代码自觉</b>。
 *
 * ★def 里写的是<b>数据库字段名</b>（record_date），不是 Java 属性名（recordDate）。
 * Spring Data 建索引时<b>不会</b>替你把属性名换成 @Field 的名字——写属性名的话
 * 建出来的是"按一个文档里根本不存在的字段排"的索引：所有文档在该列上都是 null，
 * 于第二个用户之外的任何一天都撞唯一键（E11000），完全不拦重复。
 * 现在这台库上看不出来，是因为种子脚本先建了同名且键正确的 uk_user_date，
 * 而 Spring Data 建索引按<b>名字</b>幂等跳过——错误被同名索引挡住了，换个新库就暴露。
 *
 * 只建这一条，不建 (userId, record_date:-1) 那种降序索引：Mongo 的单字段/复合索引
 * 正序倒序都能反向扫，加第二条纯粹是每次写多维护一份。索引不是越多越好，这条要记住。
 */
@CompoundIndex(name = "uk_user_date", def = "{'userId': 1, 'record_date': 1}", unique = true)
public class BodyRecord {

    /**
     * Mongo 的主键。类型是 String，值是 ObjectId 的十六进制串。
     * <p>★不自己生成业务号：这条数据不对外暴露、不参与跨库关联，
     *   ObjectId 自带时间序（前 4 字节是创建时间戳），够用且零成本。
     *   订单号那类"要对外、要防泄露业务量"的场景才需要自己发号。</p>
     */
    private String id;

    /** 归属用户，逻辑关联 yd_user.t_user.id。★不在 Mongo 里冗余 nickname，理由同社区域那次的教训 */
    private Long userId;

    /**
     * 记录日期，格式 {@code yyyy-MM-dd}，<b>故意用字符串而不是日期类型</b>。
     * <p>三个理由：① 字符串的字典序就是时间序，范围比较（{@code >= 起 && <= 止}）直接可用；
     * ② 不会有"按 UTC 零点还是本地零点存"的时区歧义——Mongo 的 Date 精度是毫秒，
     * 存"天"就必须自己截断，跨时区部署时会出现同一天两条记录；
     * ③ 唯一索引的键值稳定，同一天永远算出同一个字符串。</p>
     */
    @Field("record_date")
    private String recordDate;

    // ==================== 指标：全部可空（体重除外，由服务层校验必填）====================

    /** 体重 kg，1 位小数。范围 20~300，超范围在服务层直接拒（防止小数点漏输把 72.5 录成 725） */
    private BigDecimal weight;

    /** 体脂率 %，1 位小数。范围 3~60。来自家用体脂秤手抄，不是实时设备 */
    @Field("body_fat_rate")
    private BigDecimal bodyFatRate;

    /** 腰围 cm。皮尺可测，减脂期它比体重更诚实（体重掉而腰围不掉，多半掉的是肌肉） */
    @Field("waist_cm")
    private BigDecimal waistCm;

    /** 胸围 cm。增肌人群用，属于"进阶围度"，前端折叠在更多里 */
    @Field("chest_cm")
    private BigDecimal chestCm;

    /** 臀围 cm。同上 */
    @Field("hip_cm")
    private BigDecimal hipCm;

    /** 睡眠时长，小时，0.5 步进。范围 0~24。它常常是"今天体重莫名涨了"的真凶 */
    @Field("sleep_hours")
    private BigDecimal sleepHours;

    /** 精力自评 1~5（5 最好）。纯手动点按，零采集成本，教练排强度最想要这个数 */
    @Field("energy_level")
    private Integer energyLevel;

    /** 肌肉酸痛 0~3（0 无 / 1 轻 / 2 中 / 3 重）。★0 是合法值"不酸"，所以这一项用整型枚举而不是"空=不酸" */
    @Field("soreness_level")
    private Integer sorenessLevel;

    /** 备注，≤40 字。"空腹/聚餐/生理期"这类标注让一次异常波动变得可解释 */
    private String note;

    /**
     * 最后修改时间。★不是 created_at：一天一条会被覆盖，"这条今天几点改过"比"什么时候建的"有用。
     * 列表页那句"今天 21:30 更新过"读的就是它。
     */
    @Field("updated_at")
    private LocalDateTime updatedAt;
}
