package com.yuedong.point.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件。
 *
 * <p>★<b>这个配置是切片 G 补上的，补的是一个"不报错但结果是错的"的坑</b>：
 * {@code selectPage} / 带 {@code Page} 参数的自定义查询都依赖这条拦截器去拼 LIMIT 和补 COUNT(*)。
 * 没配它的表现是——SQL 照常执行、接口照常返回 200，
 * 但<b>一次把整张表捞回来，且 total 恒为 0</b>。
 * 本模块里先前就有两处这样静默失效的读口（积分商城 {@code /inner/point/gift/page}），
 * 加上这次的月榜归档分页，才一起暴露出来。</p>
 *
 * <p>★为什么三个服务各配一份而不是抽到 common：它是<b>启动期装配</b>，
 * 放在 common 里会让所有服务（包括不需要分页的 user/gateway）都被动开启一个拦截器。
 * 六个字的配置重复三次，比一个隐式全局开关便宜。</p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
