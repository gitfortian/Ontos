package com.ontos.module.agent.service.terminal;

import java.util.Map;
import java.util.Set;

import com.ontos.framework.web.core.context.RequestContext;
import com.ontos.module.query.service.copy.CopyTemplateService;
import com.ontos.module.session.service.session.SessionStateService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 澄清终态的 degraded 透传（O-2 收口）：模型超时后确定性降级也会落到澄清，
 * 响应必须能区分「模型主动澄清」与「降级澄清」，否则评测侧会把降级误判成模型行为。
 */
class TerminalAssemblerTest {

    private final TerminalAssembler assembler = new TerminalAssembler(
            Mockito.mock(CopyTemplateService.class), Mockito.mock(SessionStateService.class));

    private static RequestContext context() {
        return new RequestContext("tenant-demo", "user-dev", "session-1", 1,
                "2026.11.12-twenty-second", "trace-o2", Set.of("analyst"), true, null);
    }

    @Test
    void clarificationCarriesDegradedFlag() {
        Map<String, Object> terminal = assembler.clarification(context(), null, true);
        assertEquals("clarification", terminal.get("terminal"));
        assertEquals(Boolean.TRUE, terminal.get("degraded"));
    }

    @Test
    void clarificationDefaultsToNotDegraded() {
        Map<String, Object> terminal = assembler.clarification(context(), null, false);
        assertEquals("clarification", terminal.get("terminal"));
        assertEquals(Boolean.FALSE, terminal.get("degraded"));
    }

    @Test
    void clarificationStillEmitsFourElements() {
        Map<String, Object> terminal = assembler.clarification(context(), null, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) terminal.get("body");
        assertEquals(true, body.containsKey("ambiguity"));
        assertEquals(true, body.containsKey("candidates"));
        assertEquals(true, body.containsKey("defaultSuggestion"));
        assertEquals(true, body.containsKey("skippable"));
        assertEquals("trace-o2", terminal.get("requestId"));
    }
}
