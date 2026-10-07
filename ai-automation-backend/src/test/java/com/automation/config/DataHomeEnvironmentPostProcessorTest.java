package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataHomeEnvironmentPostProcessorTest {

    private final DataHomeEnvironmentPostProcessor processor = new DataHomeEnvironmentPostProcessor();

    @Test
    void should_addDataHomeAsLastPropertySource_when_notConfigured() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(DataHomeEnvironmentPostProcessor.PROPERTY))
                .isEqualTo(DataHome.dir().toString());
        PropertySource<?> last = environment.getPropertySources().stream().reduce((a, b) -> b).orElseThrow();
        assertThat(last.getName()).isEqualTo("dataHome");
    }

    @Test
    void should_notTouchPropertySources_when_propertyAlreadyConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DataHomeEnvironmentPostProcessor.PROPERTY, "/configured");
        int before = environment.getPropertySources().size();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(DataHomeEnvironmentPostProcessor.PROPERTY)).isEqualTo("/configured");
        assertThat(environment.getPropertySources().size()).isEqualTo(before);
        assertThat(environment.getPropertySources().contains("dataHome")).isFalse();
    }

    @Test
    void should_letALaterAddedSourceWin_becauseDataHomeIsAddedLast() {
        MockEnvironment environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());

        environment.getPropertySources().addFirst(new MapPropertySource("override",
                Map.of(DataHomeEnvironmentPostProcessor.PROPERTY, "/override")));

        assertThat(environment.getProperty(DataHomeEnvironmentPostProcessor.PROPERTY)).isEqualTo("/override");
    }

    @Test
    void should_useTheDocumentedPropertyName() {
        assertThat(DataHomeEnvironmentPostProcessor.PROPERTY).isEqualTo("automation.data-home");
    }
}
