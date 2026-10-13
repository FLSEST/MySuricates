package com.automation.service.mcp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolTest {

    /**
     * The destructive flag becomes {@code destructiveHint}, on which an MCP client decides whether to ask
     * the user. A bare {@code true}/{@code false} after a long schema is easy to get wrong unnoticed, so the
     * kind of tool is chosen by the factory's name instead (Java audit finding e5b1067f).
     */
    @Test
    void noPublicFactoryOrConstructorTakesABareFlag() {
        List<Executable> entries = new ArrayList<>(Arrays.asList(McpTool.class.getConstructors()));
        for (Method method : McpTool.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == McpTool.class) {
                entries.add(method);
            }
        }

        assertThat(entries).isNotEmpty();
        assertThat(entries)
                .filteredOn(entry -> Arrays.stream(entry.getParameterTypes())
                        .anyMatch(type -> type == boolean.class || type == Boolean.class))
                .as("public ways to build an McpTool that take a boolean")
                .isEmpty();
    }
}
