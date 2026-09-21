package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoSimilarityConfigTest {

    /** Binds like Spring Boot does at startup: kebab-case keys below the class's own prefix. */
    private static TodoSimilarityConfig bind(Map<String, String> properties) {
        String prefix = TodoSimilarityConfig.class.getAnnotation(ConfigurationProperties.class).prefix();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(prefix, Bindable.of(TodoSimilarityConfig.class));
    }

    @Test
    void should_be_a_configuration_with_prefix_automation_todo_similarity() {
        assertThat(TodoSimilarityConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(TodoSimilarityConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.todo.similarity");
    }

    @Test
    void should_default_keyword_threshold_to_0_2() {
        assertThat(new TodoSimilarityConfig().getKeywordThreshold()).isEqualTo(0.2);
    }

    @Test
    void should_keep_default_threshold_within_the_jaccard_range() {
        assertThat(new TodoSimilarityConfig().getKeywordThreshold()).isBetween(0.0, 1.0);
    }

    @Test
    void should_bind_keyword_threshold() {
        TodoSimilarityConfig config = bind(Map.of("automation.todo.similarity.keyword-threshold", "0.45"));

        assertThat(config.getKeywordThreshold()).isEqualTo(0.45);
    }
}
