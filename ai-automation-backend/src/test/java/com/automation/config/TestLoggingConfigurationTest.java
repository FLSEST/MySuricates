package com.automation.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class TestLoggingConfigurationTest {

    @Test
    void testLoggingUsesOnlyErrorConsoleAppender() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        assertThat(root.getLevel()).isEqualTo(Level.ERROR);
        assertThat(root.getAppender("CONSOLE")).isNotNull();
        assertThat(root.getAppender("FILE")).isNull();
        assertThat(root.getAppender("ERROR_FILE")).isNull();
    }
}
