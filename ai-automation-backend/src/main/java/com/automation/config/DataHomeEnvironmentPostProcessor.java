package com.automation.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Provides {@code automation.data-home} ({@link DataHome#dir()}) for the defaults in
 * {@code application.yml}, unless it is set already (e.g. {@code AUTOMATION_DATA_HOME}). Added as
 * the last property source, so every configured value wins. Registered in
 * {@code META-INF/spring.factories}.
 */
public class DataHomeEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY = "automation.data-home";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.containsProperty(PROPERTY)) {
            return;
        }
        environment.getPropertySources().addLast(
                new MapPropertySource("dataHome", Map.of(PROPERTY, DataHome.dir().toString())));
    }
}
