package com.automation.service.isolation;

import com.automation.config.ProcessSandboxConfiguration;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.ai.catalog.CliCommandRunner;
import com.automation.util.HostProcessUtils;
import com.automation.util.UnattendedContainer;
import com.automation.util.UnattendedContainerCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TODO 6ee57b87: what the configuration hands the container mode, and which leftover containers it removes
class UnattendedContainerConfigurationTest {

    private final ModuleRegistry modules = mock(ModuleRegistry.class);
    private final ProcessSandboxConfiguration sandbox = mock(ProcessSandboxConfiguration.class);
    private final UnattendedCliAccess cliAccess = mock(UnattendedCliAccess.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final List<List<String>> calls = new ArrayList<>();
    private String listing = "";

    private final CliCommandRunner runner = (command, timeout) -> {
        calls.add(command);
        if (command.get(1).equals("rm") && command.get(3).contains("gone")) {
            throw new IOException("No such container");
        }
        return command.get(1).equals("ps") ? listing : "";
    };

    private UnattendedContainerConfiguration configuration() {
        return new UnattendedContainerConfiguration(modules, sandbox, cliAccess, mock(HostProcessUtils.class), runner,
                environment);
    }

    @AfterEach
    void off() {
        UnattendedContainer.configure(new UnattendedContainer.Policy(false, () -> null, name -> { }));
    }

    @Test
    @DisplayName("each start gets the image, this user, the instance's secrets and the directories never to mount")
    void setup() {
        Path db = Path.of("/srv/data/automation.db");
        when(sandbox.hiddenPaths()).thenReturn(List.of(db));
        when(cliAccess.all()).thenReturn(Map.of());
        environment.setProperty("automation.unattended-container.memory", "6g");

        UnattendedContainerCommand.Setup setup = configuration().setup();

        assertThat(setup.image()).isEqualTo("automation-agent-workflow:latest");
        assertThat(setup.hiddenPaths()).containsExactly(db);
        assertThat(setup.home()).isEqualTo(Path.of(System.getProperty("user.home")));
        assertThat(setup.dataDirectories()).contains(Path.of(System.getProperty("user.home"), ".mysuricates"));
        assertThat(setup.memory()).isEqualTo("6g");
        assertThat(setup.cacheVolume()).isEqualTo("mysuricates-unattended-cache");
        assertThat(setup.ownerPid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(setup.uid()).isPositive();
    }

    @Test
    @DisplayName("the switch decides whether processes outside attended runs go into containers")
    void switchDecides() {
        when(modules.isEnabled(ModuleCatalog.UNATTENDED_IN_CONTAINER)).thenReturn(false);
        configuration().configure();
        assertThat(UnattendedContainer.active()).isFalse();
    }

    @Test
    @DisplayName("containers of a backend process that is gone are removed, those of a living one stay")
    void sweep() {
        listing = "mysuricates-unattended-a 2147483646\nmysuricates-unattended-b " + ProcessHandle.current().pid() + "\n";

        configuration().sweepOrphans();

        assertThat(calls).contains(List.of("docker", "rm", "-f", "mysuricates-unattended-a"))
                .doesNotContain(List.of("docker", "rm", "-f", "mysuricates-unattended-b"));
    }

    @Test
    @DisplayName("removing a container Docker already removed is no error")
    void removeGone() {
        configuration().remove("mysuricates-unattended-gone");

        assertThat(calls).containsExactly(List.of("docker", "rm", "-f", "mysuricates-unattended-gone"));
    }
}
