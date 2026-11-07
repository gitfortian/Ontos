package com.ontos.config;

import com.ontos.framework.web.config.WebProperties;
import com.ontos.module.system.service.policy.PolicyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevModeGuardTest {

    @Test
    void shouldAllowDevSwitchesOnlyInLocalProfile() {
        assertDoesNotThrow(() -> guard("local", true, true).run(null));
    }

    @Test
    void shouldRejectDevHeadersOutsideLocal() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("prod", true, false).run(null));
        assertTrue(ex.getMessage().contains("ontos.web.security.dev-headers"));
    }

    @Test
    void shouldRejectDevPolicyBypassOutsideLocal() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("prod", false, true).run(null));
        assertTrue(ex.getMessage().contains("ontos.policy.dev-allow-all"));
    }

    @Test
    void shouldReportBothSwitchesAtOnce() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard("staging", true, true).run(null));
        assertTrue(ex.getMessage().contains("ontos.web.security.dev-headers"));
        assertTrue(ex.getMessage().contains("ontos.policy.dev-allow-all"));
    }

    @Test
    void shouldAllowHardenedProductionDefaults() {
        assertDoesNotThrow(() -> guard("prod", false, false).run(null));
    }

    private static DevModeGuard guard(String profile, boolean devHeaders, boolean devAllowAll) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profile);
        WebProperties webProperties = new WebProperties();
        webProperties.getSecurity().setDevHeaders(devHeaders);
        PolicyProperties policyProperties = new PolicyProperties();
        policyProperties.setDevAllowAll(devAllowAll);
        return new DevModeGuard(environment, webProperties, policyProperties);
    }

}
