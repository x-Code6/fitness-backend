package com.yuedong.gateway.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 鉴权相关配置项（application.yml 里的 auth: 节点）。
 *
 * <p>白名单用"前缀匹配"：只要请求路径以配置项开头就直接放行，
 * 简单直观；后续需要更精细的规则再换 AntPathMatcher。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /** 免登录白名单（前缀匹配），如登录、注册、埋点上报 */
    private List<String> whitelist = new ArrayList<>();

    /**
     * 是否启用 Redis 令牌白名单校验。
     *
     * <p>{@code true}（默认）：验签通过后还要确认 {@code login:token:{jti}} 仍存在于 Redis，
     * 才能实现"禁用用户立即生效""踢下线"。</p>
     *
     * <p>{@code false}：只验签，等于回到纯无状态 JWT——<b>禁用与吊销全部失效</b>。
     * 留这个开关只为一种场景：本地开发时 Redis 还没起来，但又想先联调别的功能。
     * 生产/演示必须为 true。</p>
     *
     * <p>⚠️ 这里隐含一个必须知道的取舍：开启后 Redis 成为登录链路的强依赖。
     * 本项目选择 <b>fail-close</b>——Redis 查不到/连不上就判定为未登录，宁可全站要求重新登录，
     * 也不能让"已吊销的令牌"因为 Redis 抖动而重新生效。
     * 反过来的 fail-open 会把可撤销性变成"看运气"。</p>
     */
    private boolean tokenCheckEnabled = true;
}
