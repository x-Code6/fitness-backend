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
}
