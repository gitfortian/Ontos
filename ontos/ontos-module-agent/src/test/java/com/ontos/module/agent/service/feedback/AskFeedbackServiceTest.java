package com.ontos.module.agent.service.feedback;

import java.util.concurrent.atomic.AtomicReference;

import com.ontos.module.agent.dal.mysql.AskFeedbackMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 反馈服务 fail-closed 语义：封闭枚举、归属必填、UP 不带原因。 */
class AskFeedbackServiceTest {

    private final AskFeedbackMapper mapper = Mockito.mock(AskFeedbackMapper.class);
    private final AskFeedbackService service = new AskFeedbackService(mapper);

    private static AskFeedbackService.Request request(String rating, String reasonCode) {
        return new AskFeedbackService.Request("t1", "u1", "s1", null, "req-1", rating,
                reasonCode, null);
    }

    @Test
    void submitsDownWithReasonAndTrimsBlanks() {
        AtomicReference<String> capturedReason = new AtomicReference<>();
        when(mapper.insert(anyString(), anyString(), any(), any(), any(), anyString(), any(),
                any())).thenAnswer(invocation -> {
            capturedReason.set(invocation.getArgument(6));
            return 42L;
        });

        long id = service.submit(request(AskFeedbackService.DOWN, " WRONG_DATA "));

        assertEquals(42L, id);
        assertEquals("WRONG_DATA", capturedReason.get());
    }

    @Test
    void rejectsUnknownRatingAndMissingTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(request("GOOD", null)));
        assertThrows(IllegalArgumentException.class, () -> service.submit(
                new AskFeedbackService.Request("t1", "u1", "s1", null, null,
                        AskFeedbackService.UP, null, null)));
    }

    @Test
    void rejectsUnknownReasonAndUpWithReason() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(request(AskFeedbackService.DOWN, "SOMETHING")));
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(request(AskFeedbackService.UP, AskFeedbackService.REASON_OTHER)));
    }

    @Test
    void rejectsOverlongComment() {
        assertThrows(IllegalArgumentException.class, () -> service.submit(
                new AskFeedbackService.Request("t1", "u1", "s1", null, "req-1",
                        AskFeedbackService.UP, null, "长".repeat(501))));
    }
}
