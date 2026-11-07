package com.ontos.config;

import java.util.Map;

import com.ontos.framework.web.config.WebProperties;
import com.ontos.module.system.service.policy.PolicyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevModeGuardTest {

    @Test
    void shouldAllowDevSwitchesOnlyInLocalProfile() {
        assertDoesNotThrow(() -> guard("local", true, true, "strong-secret", "strong-secret").run(null));
    }

    @Test
    void shouldRejectDevHeadersOutsideLocal() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("prod", true, false, "strong-secret", "strong-secret").run(null));
        assertTrue(ex.getMessage().contains("ontos.web.security.dev-headers"));
    }

    @Test
    void shouldRejectDevPolicyBypassOutsideLocal() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("prod", false, true, "strong-secret", "strong-secret").run(null));
        assertTrue(ex.getMessage().contains("ontos.policy.dev-allow-all"));
    }

    @Test
    void shouldReportBothSwitchesAtOnce() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("staging", true, true, "strong-secret", "strong-secret").run(null));
        assertTrue(ex.getMessage().contains("ontos.web.security.dev-headers"));
        assertTrue(ex.getMessage().contains("ontos.policy.dev-allow-all"));
    }

    @Test
    void shouldAllowHardenedProductionDefaults() {
        assertDoesNotThrow(() -> guard("prod", false, false, "strong-secret", "strong-secret").run(null));
    }

    @Test
    void shouldRejectMissingCredentialsOutsideLocal() {
        // 不注入凭据：非 local 环境必须显式提供，缺失即启动失败（T1.3 弱口令整改）
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("prod", false, false, null, null).run(null));
        assertTrue(ex.getMessage().contains("ontos.datasource.system.username/password"));
        assertTrue(ex.getMessage().contains("ontos.datasource.query.username/password"));
    }

    @Test
    void shouldRejectKnownWeakPasswordOutsideLocal() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("prod", false, false, "123456", "strong-secret").run(null));
        assertTrue(ex.getMessage().contains("ontos.datasource.system.password"));
        assertTrue(ex.getMessage().contains("弱口令"));
    }

    @Test
    void shouldRejectQueryPoolWeakPasswordOutsideLocal() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("prod", false, false, "strong-secret", "ontos_query_ro").run(null));
        assertTrue(ex.getMessage().contains("ontos.datasource.query.password"));
    }

    @Test
    void shouldAllowWeakPasswordInLocalProfile() {
        // local profile 的弱口令只允许出现在 application-local.yml；守卫对 local 全部放行
        assertDoesNotThrow(() -> guard("local", true, true, "123456", "ontos_query_ro").run(null));
    }

    private static DevModeGuard guard(String profile, boolean devHeaders, boolean devAllowAll,
                                      String systemPassword, String queryPassword) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profile);
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("ontos.datasource.system.username", systemPassword == null ? null : "ontos_admin");
        props.put("ontos.datasource.system.password", systemPassword);
        props.put("ontos.datasource.query.username", queryPassword == null ? null : "ontos_query_ro");
        props.put("ontos.datasource.query.password", queryPassword);
        environment.getPropertySources().addFirst(new MapPropertySource("test", props));
        WebProperties webProperties = new WebProperties();
        webProperties.getSecurity().setDevHeaders(devHeaders);
        PolicyProperties policyProperties = new PolicyProperties();
        policyProperties.setDevAllowAll(devAllowAll);
        return new DevModeGuard(environment, webProperties, policyProperties);
    }

}
