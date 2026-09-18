package com.automation.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WebSocket handshake cannot carry a header (SockJS and raw WebSocket both send only the URL),
 * so the API key is replaced by a short-lived, single-use ticket. These tests pin the three
 * properties the replacement depends on: single use, expiry, and no unbounded growth.
 */
class WebSocketTicketServiceTest {

    /** Test clock that can be moved forward — the service only ever reads {@code instant()}. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-18T20:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration amount) { now = now.plus(amount); }
    }

    private final MutableClock clock = new MutableClock();
    private final WebSocketTicketService service = new WebSocketTicketService(clock, 30);

    @Test
    void issuedTicketIsAcceptedExactlyOnce() {
        String ticket = service.issue();

        assertThat(service.consume(ticket)).isTrue();
        assertThat(service.consume(ticket)).as("a replayed ticket must not open a second socket").isFalse();
    }

    @Test
    void issuedTicketsAreUnguessableAndUnique() {
        String first = service.issue();
        String second = service.issue();

        assertThat(first).isNotEqualTo(second);
        // 32 random bytes, URL-safe Base64 without padding = 43 chars, safe inside a query string
        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void expiredTicketIsRejected() {
        String ticket = service.issue();

        clock.advance(Duration.ofSeconds(31));

        assertThat(service.consume(ticket)).isFalse();
    }

    @Test
    void ticketIsStillValidJustBeforeExpiry() {
        String ticket = service.issue();

        clock.advance(Duration.ofSeconds(29));

        assertThat(service.consume(ticket)).isTrue();
    }

    @Test
    void unknownTicketIsRejected() {
        assertThat(service.consume("not-a-ticket")).isFalse();
    }

    @Test
    void nullOrBlankTicketIsRejected() {
        assertThat(service.consume(null)).isFalse();
        assertThat(service.consume("")).isFalse();
        assertThat(service.consume("   ")).isFalse();
    }

    @Test
    void expiredTicketsArePurgedOnIssue() {
        service.issue();
        service.issue();
        assertThat(service.activeTicketCount()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(31));
        service.issue();

        assertThat(service.activeTicketCount()).as("only the fresh ticket is left").isEqualTo(1);
    }

    @Test
    void expiredTicketsArePurgedOnConsume() {
        String stale = service.issue();
        service.issue();

        clock.advance(Duration.ofSeconds(31));
        service.consume(stale);

        assertThat(service.activeTicketCount()).isZero();
    }

    @Test
    void consumingDoesNotAffectOtherTickets() {
        String first = service.issue();
        String second = service.issue();

        assertThat(service.consume(first)).isTrue();
        assertThat(service.consume(second)).isTrue();
    }

    @Test
    void ttlIsReportedForTheClient() {
        assertThat(service.getTtlSeconds()).isEqualTo(30);
    }
}
