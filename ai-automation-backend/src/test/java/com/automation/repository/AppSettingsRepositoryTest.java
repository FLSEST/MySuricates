package com.automation.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AppSettingsRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AppSettingsRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AppSettingsRepository(jdbcTemplate);
    }

    // --- Annotation tests ---

    @Test
    void should_haveRepositoryAnnotation_when_classIsInspected() {
        assertThat(AppSettingsRepository.class.isAnnotationPresent(Repository.class))
                .as("AppSettingsRepository must be annotated with @Repository")
                .isTrue();
    }

    // --- get: Happy path ---

    @Test
    void should_returnValue_when_keyExists() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("api-key")))
                .thenReturn(List.of("my-secret-key"));

        Optional<String> result = repository.get("api-key");

        assertThat(result).isPresent().contains("my-secret-key");
        verify(jdbcTemplate).query(contains("SELECT value FROM app_settings"), any(RowMapper.class), eq("api-key"));
    }

    @Test
    void should_returnEmpty_when_keyDoesNotExist() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("nonexistent")))
                .thenReturn(Collections.emptyList());

        Optional<String> result = repository.get("nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void should_returnFirstValue_when_multipleResultsExist() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("dup-key")))
                .thenReturn(List.of("first", "second"));

        Optional<String> result = repository.get("dup-key");

        assertThat(result).isPresent().contains("first");
    }

    // --- get: SQL verification ---

    @Test
    void should_queryWithCorrectSql_when_getCalled() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(Collections.emptyList());

        repository.get("test-key");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("test-key"));
        assertThat(sqlCaptor.getValue()).contains("SELECT value FROM app_settings WHERE key = ?");
    }

    // --- set: Happy path ---

    @Test
    void should_executeUpsert_when_setCalled() {
        repository.set("theme", "dark");

        verify(jdbcTemplate).update(
                contains("INSERT INTO app_settings"),
                eq("theme"),
                eq("dark")
        );
    }

    @Test
    void should_useOnConflictClause_when_setCalled() {
        repository.set("key", "value");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("key"), eq("value"));
        assertThat(sqlCaptor.getValue()).contains("ON CONFLICT(key) DO UPDATE SET value = excluded.value");
    }

    // --- set: Edge cases ---

    @Test
    void should_passEmptyValue_when_setCalledWithEmptyString() {
        repository.set("key", "");

        verify(jdbcTemplate).update(anyString(), eq("key"), eq(""));
    }

    @Test
    void should_passNullKey_when_setCalledWithNull() {
        repository.set(null, "value");

        verify(jdbcTemplate).update(anyString(), isNull(), eq("value"));
    }

    @Test
    void should_passNullValue_when_setCalledWithNullValue() {
        repository.set("key", null);

        verify(jdbcTemplate).update(anyString(), eq("key"), isNull());
    }

    // --- get: Edge cases ---

    @Test
    void should_passNullKey_when_getCalledWithNull() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), isNull()))
                .thenReturn(Collections.emptyList());

        Optional<String> result = repository.get(null);

        assertThat(result).isEmpty();
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), isNull());
    }

    @Test
    void should_returnEmptyStringValue_when_keyExistsWithEmptyValue() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("empty-val")))
                .thenReturn(List.of(""));

        Optional<String> result = repository.get("empty-val");

        assertThat(result).isPresent().contains("");
    }
}
