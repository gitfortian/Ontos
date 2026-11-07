package com.ontos.module.system.service.request;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestStatusTest {

    @Test
    void shouldAllowLegalTransitions() {
        assertTrue(RequestStatus.RECEIVED.canTransitionTo(RequestStatus.PROPOSED));
        assertTrue(RequestStatus.RECEIVED.canTransitionTo(RequestStatus.CLARIFYING));
        assertTrue(RequestStatus.RECEIVED.canTransitionTo(RequestStatus.REJECTED));
        assertTrue(RequestStatus.CLARIFYING.canTransitionTo(RequestStatus.PROPOSED));
        assertTrue(RequestStatus.CLARIFYING.canTransitionTo(RequestStatus.EXPIRED));
        assertTrue(RequestStatus.PROPOSED.canTransitionTo(RequestStatus.APPROVAL_PENDING));
        assertTrue(RequestStatus.PROPOSED.canTransitionTo(RequestStatus.EXECUTED));
        assertTrue(RequestStatus.APPROVAL_PENDING.canTransitionTo(RequestStatus.EXECUTED));
        assertTrue(RequestStatus.APPROVAL_PENDING.canTransitionTo(RequestStatus.EXPIRED));
    }

    @Test
    void shouldRejectIllegalTransitions() {
        // 终态无出边
        for (RequestStatus terminal : new RequestStatus[] {
                RequestStatus.EXECUTED, RequestStatus.REJECTED, RequestStatus.EXPIRED, RequestStatus.FAILED}) {
            assertFalse(terminal.canTransitionTo(RequestStatus.PROPOSED), terminal + " 是终态，无出边");
            assertFalse(terminal.canTransitionTo(terminal), terminal + " 终态不可自迁");
        }
        // 跳级与倒退非法
        assertFalse(RequestStatus.RECEIVED.canTransitionTo(RequestStatus.EXECUTED));
        assertFalse(RequestStatus.RECEIVED.canTransitionTo(RequestStatus.APPROVAL_PENDING));
        assertFalse(RequestStatus.PROPOSED.canTransitionTo(RequestStatus.RECEIVED));
        assertFalse(RequestStatus.CLARIFYING.canTransitionTo(RequestStatus.FAILED));
    }

    @Test
    void shouldMarkTerminals() {
        assertTrue(RequestStatus.EXECUTED.isTerminal());
        assertTrue(RequestStatus.REJECTED.isTerminal());
        assertTrue(RequestStatus.EXPIRED.isTerminal());
        assertTrue(RequestStatus.FAILED.isTerminal());
        assertFalse(RequestStatus.RECEIVED.isTerminal());
        assertFalse(RequestStatus.PROPOSED.isTerminal());
        assertFalse(RequestStatus.APPROVAL_PENDING.isTerminal());
    }

}
