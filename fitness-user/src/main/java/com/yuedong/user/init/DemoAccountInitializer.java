package com.yuedong.user.init;

import com.yuedong.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示账号初始化器：服务启动时把两个演示用户写进 {@code t_user}。
 *
 * <p><b>为什么不在 SQL 脚本里预置账号</b>：BCrypt 散列串必须和实际加密器同源。
 * 旧脚本里那串 {@code $2b$10$...} 来自非 Java 工具，它能否被
 * {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder} 校验通过
 * 从来没被验证过（那套库甚至从没导入运行过）。散列写死在 SQL 里，
 * 一旦前缀/版本不匹配，症状是"密码没错但一直提示账号或密码错误"，非常难查。
 * 现在改成启动时 {@code passwordEncoder.encode(...)} 现算，两边一定同源。</p>
 *
 * <p><b>为什么 id 必须是 10001/10002</b>：{@code sql/03_yd_order.sql} 里的历史订单、
 * 凭证、预约、评价种子数据都按这两个 id 写死了。id 对不上，"我的订单""评分""榜单"
 * 首屏就会是空的，看起来像功能坏了。</p>
 *
 * <p><b>开关</b>：{@code demo.account.enabled}，默认 true。它是演示数据，
 * 真上线要用 {@code -Ddemo.account.enabled=false} 关掉——
 * 否则任何人都能用 13800008819 / 123456 登进你的生产库。</p>
 *
 * <p><b>失败为什么不抛出异常</b>：本机开发时数据库可能还没起（或还没导脚本）。
 * 让整个服务因为"演示数据没插上"而启动失败太粗暴；这里记 error 日志后继续启动，
 * 登录会返回"账号或密码错误"，而日志里能找到真正原因。</p>
 */
@Slf4j
@Order(10)                                   // 排在业务 Bean 就绪之后即可，没有强顺序依赖
@Component
@RequiredArgsConstructor
public class DemoAccountInitializer implements CommandLineRunner {

    /** 演示账号统一口令。只用于本地演示，不参与任何真实认证场景 */
    private static final String DEMO_PASSWORD = "123456";

    /** 手机号 / 昵称 / 头像配色下标，与前端 AVATAR_COLORS 的 0~5 对应 */
    private static final String[][] DEMO_ACCOUNTS = {
            {"10001", "13800008819", "小鱼同学", "0"},
            {"10002", "13800008820", "爱运动的鹿", "3"},
    };

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /** 演示数据开关：true=启动时写入，false=完全跳过 */
    @Value("${demo.account.enabled:true}")
    private boolean demoAccountEnabled;

    @Override
    public void run(String... args) {
        if (!demoAccountEnabled) {
            log.info("演示账号初始化已关闭（demo.account.enabled=false），跳过");
            return;
        }

        // 口令只在启动时散列一次，两个账号共用；BCrypt 自带随机盐，
        // 所以同一个明文两次加密结果不同，这是正常的，不要拿字符串比较它
        String encodedPassword = passwordEncoder.encode(DEMO_PASSWORD);

        try {
            for (String[] account : DEMO_ACCOUNTS) {
                Long id = Long.valueOf(account[0]);
                // 幂等插入：撞主键或 uk_phone 时什么也不改（见 UserMapper#insertDemoIfAbsent 注释）
                int rows = userMapper.insertDemoIfAbsent(id, account[1], encodedPassword, account[2], account[3]);
                log.info("演示账号写入: id={}, phone={}, 新插入={}", account[0], account[1], rows == 1);
            }
        } catch (Exception e) {
            // 常见原因：数据库还没建库、还没导 SQL、或连接信息不对。
            // 这里不阻断启动，但要把原因写清楚，否则只看到"登录失败"会往认证逻辑上查
            log.error("演示账号初始化失败（不影响服务启动，但登录会报\"手机号或密码错误\"）：" +
                    "请确认 yd_user 库与 sql/01_yd_user.sql 已导入。原因：{}", e.getMessage());
        }
    }
}
