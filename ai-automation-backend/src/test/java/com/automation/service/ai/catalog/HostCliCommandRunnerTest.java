package com.automation.service.ai.catalog;

import com.automation.util.HostProcessUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({OS.LINUX, OS.MAC})
class HostCliCommandRunnerTest {

    private final HostCliCommandRunner runner = new HostCliCommandRunner(new HostProcessUtils());

    @Test
    void run_returnsTrimmedStdout() throws IOException {
        String out = runner.run(List.of("sh", "-c", "printf '  hello\\n'"), 5);

        assertThat(out).isEqualTo("hello");
    }

    @Test
    void run_nonZeroExit_throwsWithStderrInMessage() {
        assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "echo 'no auth configured' >&2; exit 3"), 5))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exited with 3")
                .hasMessageContaining("no auth configured");
    }

    @Test
    void run_missingBinary_throwsIOException() {
        assertThatThrownBy(() -> runner.run(List.of("definitely-not-a-real-binary-xyz"), 5))
                .isInstanceOf(IOException.class);
    }
}
