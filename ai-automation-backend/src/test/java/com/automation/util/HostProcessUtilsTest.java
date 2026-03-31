package com.automation.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostProcessUtilsTest {

    @Test
    void getHostUid_configured_returnsConfigured() {
        HostProcessUtils utils = new HostProcessUtils();
        ReflectionTestUtils.setField(utils, "configuredHostUid", "5000");

        assertThat(utils.getHostUid()).isEqualTo("5000");
    }

    @Test
    void getHostUid_notConfigured_runsIdCommand() {
        HostProcessUtils utils = new HostProcessUtils();
        ReflectionTestUtils.setField(utils, "configuredHostUid", null);

        String uid = utils.getHostUid();

        // On Linux, id -u returns a numeric UID
        assertThat(uid).matches("\\d+");
    }

    @Test
    void getHostUid_cached_returnsCached() {
        HostProcessUtils utils = new HostProcessUtils();
        ReflectionTestUtils.setField(utils, "configuredHostUid", null);

        String first = utils.getHostUid();
        String second = utils.getHostUid();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void drain_successfulProcess_returnsOutput() throws Exception {
        HostProcessUtils utils = new HostProcessUtils();
        Process proc = new ProcessBuilder("echo", "hello world").start();

        String output = utils.drain(proc, 10, "test");

        assertThat(output).isEqualTo("hello world");
    }

    @Test
    void drain_timeout_throwsIOException() throws Exception {
        HostProcessUtils utils = new HostProcessUtils();
        Process proc = new ProcessBuilder("sleep", "60").start();

        assertThatThrownBy(() -> utils.drain(proc, 1, "test"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");

        proc.destroyForcibly();
    }
}
