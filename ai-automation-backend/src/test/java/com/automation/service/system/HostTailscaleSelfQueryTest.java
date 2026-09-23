package com.automation.service.system;

import com.automation.util.HostProcessUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The CLI wrapper without a real Tailscale: a missing binary, a failing one, and {@code echo} in
 * place of {@code tailscale}, which prints back exactly the arguments the wrapper passes — so the two
 * read-only commands are pinned, and nothing like {@code up} or {@code down} can slip in.
 */
class HostTailscaleSelfQueryTest {

    private static boolean onPath(String command) {
        return new HostProcessUtils().isOnPath(command);
    }

    @Test
    void missingBinary_answersEmpty() {
        HostTailscaleSelfQuery query = new HostTailscaleSelfQuery("mysuricates-no-such-tailscale-binary");

        assertThat(query.ipv4()).isEmpty();
        assertThat(query.statusJson()).isEmpty();
    }

    @Test
    void failingCommand_answersEmpty() {
        assumeTrue(onPath("false"), "needs the POSIX false command");

        assertThat(new HostTailscaleSelfQuery("false").ipv4()).isEmpty();
    }

    @Test
    void asksOnlyTheTwoReadOnlyQuestions() {
        assumeTrue(onPath("echo"), "needs the POSIX echo command");
        HostTailscaleSelfQuery query = new HostTailscaleSelfQuery("echo");

        assertThat(query.ipv4()).contains("ip -4");
        assertThat(query.statusJson()).contains("status --json");
    }
}
