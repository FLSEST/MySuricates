package com.automation.service.system;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class OwnTailnetAddressesTest {

    /** Self plus one peer in the same tailnet — only Self may come out. */
    private static final String RUNNING_STATUS = """
            {"BackendState": "Running",
             "Self": {"DNSName": "mine.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.7", "fd7a:115c:a1e0::7"]},
             "Peer": {"nodekey:abc": {"DNSName": "peer.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.8"]}}}
            """;

    private final AtomicLong nanoTime = new AtomicLong();

    /** Stands in for the CLI: canned answers, counted calls. */
    private static final class FakeTailscale implements TailscaleSelfQuery {
        Optional<String> ipv4 = Optional.empty();
        Optional<String> status = Optional.empty();
        int ipv4Calls;
        int statusCalls;

        static FakeTailscale running() {
            FakeTailscale fake = new FakeTailscale();
            fake.ipv4 = Optional.of("100.64.0.7\n");
            fake.status = Optional.of(RUNNING_STATUS);
            return fake;
        }

        @Override
        public Optional<String> ipv4() {
            ipv4Calls++;
            return ipv4;
        }

        @Override
        public Optional<String> statusJson() {
            statusCalls++;
            return status;
        }
    }

    private OwnTailnetAddresses withModule(boolean remoteAccess, FakeTailscale tailscale) {
        return new OwnTailnetAddresses(() -> remoteAccess, tailscale, nanoTime::get);
    }

    @Test
    void remoteAccessOff_asksNothingAndKnowsNothing() {
        FakeTailscale tailscale = FakeTailscale.running();

        assertThat(withModule(false, tailscale).hosts()).isEmpty();
        assertThat(tailscale.ipv4Calls + tailscale.statusCalls).isZero();
    }

    @Test
    void running_yieldsOwnIpv4AndMagicDnsName_neverAPeer() {
        assertThat(withModule(true, FakeTailscale.running()).hosts())
                .containsExactlyInAnyOrder("100.64.0.7", "mine.tail1234.ts.net");
    }

    @Test
    void withoutTailscale_isEmpty() {
        assertThat(withModule(true, new FakeTailscale()).hosts()).isEmpty();
    }

    @Test
    void stoppedTailscale_isEmpty() {
        FakeTailscale stopped = new FakeTailscale();
        stopped.status = Optional.of("{\"BackendState\": \"Stopped\", \"Self\": {\"DNSName\": \"\"}}");

        assertThat(withModule(true, stopped).hosts()).isEmpty();
    }

    @Test
    void unusableOutput_isIgnoredRatherThanTrusted() {
        FakeTailscale odd = new FakeTailscale();
        odd.ipv4 = Optional.of("no current Tailscale IPs; state: Stopped\n999.1.1.1\n100.64.0.7 extra");
        odd.status = Optional.of("{\"Self\": {\"DNSName\": \"evil host/../x\"}}");

        assertThat(withModule(true, odd).hosts()).isEmpty();

        odd.status = Optional.of("Warning: not json");
        assertThat(withModule(true, odd).hosts()).isEmpty();
    }

    @Test
    void answerIsKeptForTheTtl_thenAskedAgain() {
        FakeTailscale tailscale = FakeTailscale.running();
        OwnTailnetAddresses own = withModule(true, tailscale);

        own.hosts();
        own.hosts();
        assertThat(tailscale.ipv4Calls).isEqualTo(1);
        assertThat(tailscale.statusCalls).isEqualTo(1);

        nanoTime.addAndGet(OwnTailnetAddresses.TTL.toNanos());
        own.hosts();
        assertThat(tailscale.ipv4Calls).isEqualTo(2);
        assertThat(tailscale.statusCalls).isEqualTo(2);
    }

    @Test
    void addressHandedOutLater_showsUpWithoutRestart() {
        FakeTailscale tailscale = new FakeTailscale();
        OwnTailnetAddresses own = withModule(true, tailscale);
        assertThat(own.hosts()).isEmpty();

        tailscale.ipv4 = Optional.of("100.64.0.7\n");
        tailscale.status = Optional.of(RUNNING_STATUS);
        nanoTime.addAndGet(OwnTailnetAddresses.TTL.toNanos());

        assertThat(own.hosts()).contains("100.64.0.7", "mine.tail1234.ts.net");
    }

    @Test
    void normalizeHost_lowercasesAndDropsTheTrailingDot() {
        assertThat(OwnTailnetAddresses.normalizeHost("Mine.Tail1234.TS.net.")).isEqualTo("mine.tail1234.ts.net");
        assertThat(OwnTailnetAddresses.normalizeHost("100.64.0.7")).isEqualTo("100.64.0.7");
    }
}
