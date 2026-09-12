package com.automation.service.integration.providers.taskwarrior;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskwarriorArgsTest {

    @Test
    void requireParam_blank_throws() {
        assertThatThrownBy(() -> TaskwarriorArgs.requireParam(Map.of("id", "   "), "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Required parameter missing: id");
        assertThatThrownBy(() -> TaskwarriorArgs.requireParam(Map.of(), "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Required parameter missing: id");
    }

    @Test
    void requireId_rejectsNonUuidNonNumeric() {
        assertThatThrownBy(() -> TaskwarriorArgs.requireId(Map.of("id", "not a valid id!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid task id: not a valid id! (expected UUID or numeric id)");
    }

    @Test
    void buildAttributeArgs_invalidPriority_throws() {
        assertThatThrownBy(() -> TaskwarriorArgs.buildAttributeArgs(Map.of("priority", "X")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid priority: X (expected H, M or L)");
    }

    @Test
    void extractTags_rejectsWhitespace() {
        assertThatThrownBy(() -> TaskwarriorArgs.extractTags(List.of("bad tag")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid tag (whitespace not allowed): bad tag");
    }

    @Test
    void sanitizeFilterTokens_rejectsRcOverride() {
        assertThatThrownBy(() -> TaskwarriorArgs.sanitizeFilterTokens("rc.verbose=nothing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Disallowed filter token (rc. overrides not permitted): rc.verbose=nothing");
    }

    @Test
    void sanitizeFilterTokens_rejectsCommandKeyword() {
        assertThatThrownBy(() -> TaskwarriorArgs.sanitizeFilterTokens("execute"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Disallowed filter token (Taskwarrior command keyword): execute");
    }

    @Test
    void envFrom_splitsTaskdataAndTaskrc() {
        Map<String, String> env = TaskwarriorArgs.envFrom("/data|/rc");
        assertThat(env).containsEntry("TASKDATA", "/data").containsEntry("TASKRC", "/rc");
    }

    @Test
    void envFrom_null_returnsEmptyEnv() {
        assertThat(TaskwarriorArgs.envFrom(null)).isEmpty();
    }
}
