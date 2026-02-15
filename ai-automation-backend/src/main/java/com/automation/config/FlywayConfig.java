package com.automation.config;

import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Statement;

@Configuration
public class FlywayConfig {

    @Bean
    public Callback flywayCallback() {
        return new BaseCallback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.BEFORE_MIGRATE;
            }

            @Override
            public boolean canHandleInTransaction(Event event, Context context) {
                return true;
            }

            @Override
            public void handle(Event event, Context context) {
                try (Statement stmt = context.getConnection().createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON");
                } catch (Exception e) {
                    throw new RuntimeException("Failed to enable foreign keys", e);
                }
            }
        };
    }
}
