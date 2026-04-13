package com.automation.config;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlywayConfigTest {

    private FlywayConfig flywayConfig;
    private Callback callback;

    @Mock
    private Context context;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @BeforeEach
    void setUp() {
        flywayConfig = new FlywayConfig();
        callback = flywayConfig.flywayCallback();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(FlywayConfig.class.isAnnotationPresent(Configuration.class))
                .as("FlywayConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveBeanAnnotation_when_flywayCallbackMethodIsInspected() throws NoSuchMethodException {
        Method method = FlywayConfig.class.getMethod("flywayCallback");
        assertThat(method.isAnnotationPresent(Bean.class))
                .as("flywayCallback() must be annotated with @Bean")
                .isTrue();
    }

    // --- Bean creation tests ---

    @Test
    void should_returnNonNullCallback_when_flywayCallbackIsCalled() {
        assertThat(callback).isNotNull();
    }

    @Test
    void should_returnCallbackInstance_when_flywayCallbackIsCalled() {
        assertThat(callback).isInstanceOf(Callback.class);
    }

    // --- supports() tests ---

    @Test
    void should_returnTrue_when_eventIsBeforeMigrate() {
        assertThat(callback.supports(Event.BEFORE_MIGRATE, context)).isTrue();
    }

    @Test
    void should_returnFalse_when_eventIsAfterMigrate() {
        assertThat(callback.supports(Event.AFTER_MIGRATE, context)).isFalse();
    }

    @Test
    void should_returnFalse_when_eventIsBeforeEachMigrate() {
        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, context)).isFalse();
    }

    @Test
    void should_returnFalse_when_eventIsAfterEachMigrate() {
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, context)).isFalse();
    }

    @Test
    void should_returnFalse_when_eventIsBeforeClean() {
        assertThat(callback.supports(Event.BEFORE_CLEAN, context)).isFalse();
    }

    @Test
    void should_returnFalse_when_eventIsBeforeValidate() {
        assertThat(callback.supports(Event.BEFORE_VALIDATE, context)).isFalse();
    }

    // --- canHandleInTransaction() tests ---

    @Test
    void should_returnTrue_when_canHandleInTransactionIsCalledWithBeforeMigrate() {
        assertThat(callback.canHandleInTransaction(Event.BEFORE_MIGRATE, context)).isTrue();
    }

    @Test
    void should_returnTrue_when_canHandleInTransactionIsCalledWithAnyEvent() {
        assertThat(callback.canHandleInTransaction(Event.AFTER_MIGRATE, context)).isTrue();
    }

    // --- handle() happy path ---

    @Test
    void should_executePragmaForeignKeys_when_handleIsCalled() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        callback.handle(Event.BEFORE_MIGRATE, context);

        verify(statement).execute("PRAGMA foreign_keys = ON");
    }

    @Test
    void should_closeStatement_when_handleCompletes() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        callback.handle(Event.BEFORE_MIGRATE, context);

        verify(statement).close();
    }

    // --- handle() error path ---

    @Test
    void should_throwRuntimeException_when_createStatementFails() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenThrow(new SQLException("Connection failed"));

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to enable foreign keys")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void should_throwRuntimeException_when_executeStatementFails() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("Execute failed"));

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to enable foreign keys")
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void should_wrapOriginalException_when_handleFails() throws Exception {
        SQLException originalCause = new SQLException("Original error");
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenThrow(originalCause);

        assertThatThrownBy(() -> callback.handle(Event.BEFORE_MIGRATE, context))
                .isInstanceOf(RuntimeException.class)
                .cause()
                .isSameAs(originalCause);
    }
}
