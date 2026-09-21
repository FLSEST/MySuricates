package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliCommandRunnerTest {

    @Test
    void isAFunctionalInterface_soSourcesCanBeFedCannedOutput() {
        assertThat(CliCommandRunner.class.isInterface()).isTrue();
        assertThat(CliCommandRunner.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }

    @Test
    void checkedIOException_reachesTheCaller() {
        CliCommandRunner missingBinary = (cmd, timeout) -> {
            throw new IOException("Cannot run program \"" + cmd.get(0) + "\"");
        };

        assertThatThrownBy(() -> missingBinary.run(List.of("pi"), 5))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("\"pi\"");
    }

    @Test
    void sourcesPassArgvAndConfiguredProbeTimeout() {
        ModelCatalogConfig config = new ModelCatalogConfig();
        config.setProbeTimeoutSeconds(7);
        List<Integer> timeouts = new ArrayList<>();
        List<List<String>> commands = new ArrayList<>();
        CliCommandRunner recording = (cmd, timeout) -> {
            commands.add(cmd);
            timeouts.add(timeout);
            return "";
        };

        new PiModelCatalogSource(config, recording).fetch();

        assertThat(commands).containsExactly(PiModelCatalogSource.COMMAND);
        assertThat(timeouts).containsExactly(7);
    }
}
