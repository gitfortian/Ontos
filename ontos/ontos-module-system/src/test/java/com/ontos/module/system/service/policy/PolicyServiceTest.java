package com.ontos.module.system.service.policy;

import java.util.Set;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.system.enums.ErrorCodeConstants;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PolicyService 守卫单测（M4/W1）：prod profile 下 dev-allow-all 禁止启用（启动失败）；
 * 非 prod 的 dev 放行语义；审计版本容错（快照缺失返回 null 不阻断审计）。
 */
class PolicyServiceTest {

    private final PolicySnapshotService snapshotService = mock(PolicySnapshotService.class);

    private Environment env(String... profiles) {
        Environment env = mock(Environment.class);
        when(env.matchesProfiles("prod")).thenReturn(Set.of(profiles).contains("prod"));
        return env;
    }

    private PolicyProperties props(boolean devAllowAll) {
        PolicyProperties properties = new PolicyProperties();
        properties.setDevAllowAll(devAllowAll);
        return properties;
    }

    private static RequestContext context() {
        return new RequestContext("demo", "u-dev", "s1", 1, null, "t", Set.of(), false);
    }

    @Test
    void shouldRefuseStartupWhenDevAllowAllOnProdProfile() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new PolicyService(snapshotService, props(true), env("prod")));
        assertTrue(ex.getMessage().contains("dev-allow-all"));
    }

    @Test
    void shouldAllowStartupWhenDevAllowAllOnNonProdProfile() {
        PolicyService service = new PolicyService(snapshotService, props(true), env("local"));
        // dev 放行：内部 INTERNAL，不触达快照
        assertEquals(PolicySnapshotService.SensitivityClass.INTERNAL,
                service.check(context(), PolicySnapshotService.AssetType.METRIC, "metric.gmv"));
        // 审计版本：dev 放行返回 null
        assertEquals(null, service.auditVersion("demo"));
    }

    @Test
    void shouldDelegateToSnapshotServiceWhenNotDevAllowed() {
        when(snapshotService.check(context(), PolicySnapshotService.AssetType.METRIC, "metric.gmv"))
                .thenReturn(PolicySnapshotService.SensitivityClass.INTERNAL);
        PolicyService service = new PolicyService(snapshotService, props(false), env("local"));
        assertEquals(PolicySnapshotService.SensitivityClass.INTERNAL,
                service.check(context(), PolicySnapshotService.AssetType.METRIC, "metric.gmv"));
    }

    @Test
    void shouldReturnNullAuditVersionWhenSnapshotMissing() {
        // 快照缺失：审计版本容错为 null（不阻断审计主流程），授权层另行拒绝
        when(snapshotService.activeVersion("demo")).thenThrow(
                com.ontos.framework.common.exception.util.ServiceExceptionUtil
                        .exception(ErrorCodeConstants.POLICY_SNAPSHOT_MISSING, "demo"));
        PolicyService service = new PolicyService(snapshotService, props(false), env("local"));
        assertEquals(null, service.auditVersion("demo"));
    }
}
