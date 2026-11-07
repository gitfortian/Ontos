package com.ontos.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.ontos.framework.web.config.WebProperties;
import com.ontos.module.system.service.policy.PolicyProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 生产环境 dev 开关与凭据自检。
 *
 * <p>身份头回退（ontos.web.security.dev-headers）与策略无条件放行（ontos.policy.dev-allow-all）
 * 只允许在 local profile 打开；任一开关在其它 profile 下为 true 即启动失败（fail-closed），
 * 不依赖人工检查配置，也不等到首个请求才暴露。
 *
 * <p>凭据维度（T1.3「弱口令整改」）：非 local profile 下，数据库账号或密码缺失、
 * 或密码命中已知本地默认弱口令集合，同样启动失败。local profile 的弱口令只允许存在于
 * {@code application-local.yml}（本地演示库），生产凭据必须经环境变量显式提供。
 *
 * <p>注意时序：本守卫是 ApplicationRunner，晚于 Hikari 连接池初始化；因此「凭据完全缺失」
 * 的场景多数会先被连接池以驱动错误拒绝（仍是 fail-closed，只是报错不够直白）。
 * 本守卫的价值在于拦截「弱口令在生产库上恰好能连上」的场景——那是最危险的静默配置错误。
 *
 * <p>对应 M0 实施规格 T1.3（配置与凭据风险整改）。
 */
@Component
public class DevModeGuard implements ApplicationRunner {

    private static final String LOCAL_PROFILE = "local";
    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of("123456", "ontos_query_ro", "postgres");
    private static final String[] POOLS = {"system", "query"};

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
        for (String pool : POOLS) {
            violations.addAll(credentialViolations(pool));
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "非 local profile 下禁止开发态开关与默认/缺失凭据，请检查配置: " + String.join(", ", violations));
        }
    }

    private List<String> credentialViolations(String pool) {
        List<String> violations = new ArrayList<>();
        String prefix = "ontos.datasource." + pool + ".";
        String username = environment.getProperty(prefix + "username");
        String password = environment.getProperty(prefix + "password");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            violations.add(prefix + "username/password（非 local 环境必须显式提供凭据）");
            return violations;
        }
        if (FORBIDDEN_PASSWORDS.contains(password.trim())) {
            violations.add(prefix + "password（命中已知本地默认弱口令，生产禁止）");
        }
        return violations;
    }

    private boolean isLocal() {
        return Arrays.asList(environment.getActiveProfiles()).contains(LOCAL_PROFILE);
    }

}
