package com.ontos.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.ontos.framework.web.config.WebProperties;
import com.ontos.module.system.service.policy.PolicyProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产环境 dev 开关自检。
 *
 * <p>身份头回退（ontos.web.security.dev-headers）与策略无条件放行（ontos.policy.dev-allow-all）
 * 只允许在 local profile 打开；任一开关在其它 profile 下为 true 即启动失败（fail-closed），
 * 不依赖人工检查配置，也不等到首个请求才暴露。
 *
 * <p>对应 M0 实施规格 T1.3（配置与凭据风险整改）。
 */
@Component
public class DevModeGuard implements ApplicationRunner {

    private static final String LOCAL_PROFILE = "local";

    private final Environment environment;
    private final WebProperties webProperties;
    private final PolicyProperties policyProperties;

    public DevModeGuard(Environment environment, WebProperties webProperties,
                        PolicyProperties policyProperties) {
        this.environment = environment;
        this.webProperties = webProperties;
        this.policyProperties = policyProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isLocal()) {
            return;
        }
        List<String> violations = new ArrayList<>();
        if (webProperties.getSecurity().isDevHeaders()) {
            violations.add("ontos.web.security.dev-headers");
        }
        if (policyProperties.isDevAllowAll()) {
            violations.add("ontos.policy.dev-allow-all");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "非 local profile 下禁止开启开发开关，请检查配置: " + String.join(", ", violations));
        }
    }

    private boolean isLocal() {
        return Arrays.asList(environment.getActiveProfiles()).contains(LOCAL_PROFILE);
    }

}
