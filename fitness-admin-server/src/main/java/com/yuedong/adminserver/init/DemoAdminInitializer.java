package com.yuedong.adminserver.init;

import com.yuedong.adminserver.mapper.AdminMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理端演示账号初始化器。
 *
 * <p>和 {@code fitness-user} 的 DemoAccountInitializer 是同一件事：
 * BCrypt 散列由本服务的加密器现算，不写死在 SQL 里。
 * 两个角色都要有，否则没法演示 RBAC——RBAC 的价值就体现在"低权限账号被拦住"，
 * 只有一个超管号的话，@RequireRole 到底有没有生效是看不出来的。</p>
 *
 * <p>登录名/口令/权限范围：</p>
 * <ul>
 *   <li>{@code admin / 123456} → SUPER：全部菜单，含会员、门店、操作日志；</li>
 *   <li>{@code operator / 123456} → OPERATOR：没有会员管理、门店管理、日志的权限，
 *       用它能当场验证拦截器真的在拦。</li>
 * </ul>
 *
 * <p>开关 {@code demo.account.enabled} 默认 true，真上线要关掉，
 * 否则等于把一个已知口令的管理员账号永久留在库里。</p>
 */
@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class DemoAdminInitializer implements CommandLineRunner {

    /** 演示口令，只用于本地演示 */
    private static final String DEMO_PASSWORD = "123456";

    /** 超管角色码，与 @RequireRole("SUPER") 及 AuthAdminController 里的菜单表一致 */
    private static final String ROLE_SUPER = "SUPER";

    /** 运营角色码：权限受限，用来演示越权被拒 */
    private static final String ROLE_OPERATOR = "OPERATOR";

    /** username / realName / role，id 固定 1 和 2（06_yd_admin.sql 的操作日志示例引用了它们） */
    private static final Object[][] DEMO_ADMINS = {
            {1L, "admin", "运营管理员", ROLE_SUPER},
            {2L, "operator", "内容运营", ROLE_OPERATOR},
    };

    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;

    /** 演示数据开关 */
    @Value("${demo.account.enabled:true}")
    private boolean demoAccountEnabled;

    @Override
    public void run(String[] args) {
        if (!demoAccountEnabled) {
            log.info("管理端演示账号初始化已关闭（demo.account.enabled=false），跳过");
            return;
        }

        // 两个账号共用一次散列结果即可（BCrypt 每次加密都会带新的随机盐，
        // 所以即使明文相同，落库的散列串也不同，这属于正常现象）
        String encodedPassword = passwordEncoder.encode(DEMO_PASSWORD);

        try {
            for (Object[] admin : DEMO_ADMINS) {
                Long id = (Long) admin[0];
                // 幂等插入：已存在则什么都不改，避免每次重启把管理员改过的密码覆盖掉
                int rows = adminMapper.insertDemoIfAbsent(id, (String) admin[1], encodedPassword,
                        (String) admin[2], (String) admin[3]);
                log.info("管理端演示账号写入: username={}, role={}, 新插入={}",
                        admin[1], admin[3], rows == 1);
            }
        } catch (Exception e) {
            // 不阻断启动：数据库没就绪时只记日志。
            // 后续登录会报"用户名或密码错误"，看到这个 error 就能定位真正原因
            log.error("管理端演示账号初始化失败（不影响服务启动，但登录会失败）：" +
                    "请确认 yd_admin 库与 sql/06_yd_admin.sql 已导入。原因：{}", e.getMessage());
        }
    }
}
