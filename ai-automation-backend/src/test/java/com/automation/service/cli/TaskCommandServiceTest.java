package com.automation.service.cli;

import com.automation.config.TaskCommandConfig;
import com.automation.service.cli.TaskCommandService.TaskResult;
import com.automation.util.HostProcessUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TaskCommandServiceTest {

    @Mock
    private TaskCommandConfig config;

    @Mock
    private HostProcessUtils hostProcessUtils;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TaskCommandService service;
    private TaskCommandService spyService;

    @BeforeEach
    void setUp() {
        service = new TaskCommandService(config, hostProcessUtils, objectMapper);
        spyService = spy(service);
    }

    // ----- TaskResult record -----

    @Nested
    @DisplayName("TaskResult.isSuccess")
    class TaskResultTests {

        @Test
        void should_return_true_when_exit_code_is_zero() {
            assertThat(new TaskResult("ok", 0).isSuccess()).isTrue();
        }

        @Test
        void should_return_false_when_exit_code_is_nonzero() {
            assertThat(new TaskResult("boom", 1).isSuccess()).isFalse();
        }

        @Test
        void should_return_false_when_exit_code_is_negative() {
            assertThat(new TaskResult("", -1).isSuccess()).isFalse();
        }

        @Test
        void should_expose_output_and_exit_code_via_accessors() {
            TaskResult result = new TaskResult("payload", 2);
            assertThat(result.output()).isEqualTo("payload");
            assertThat(result.exitCode()).isEqualTo(2);
        }
    }

    // ----- exportJson: happy path -----

    @Test
    void should_parse_json_array_into_list_of_maps_when_export_succeeds() {
        String json = "[{\"id\":1,\"description\":\"buy milk\"},{\"id\":2,\"description\":\"call mom\"}]";
        doReturn(new TaskResult(json, 0)).when(spyService).run(anyList(), any(), any());

        List<Map<String, Object>> tasks = spyService.exportJson(List.of("status:pending"), Map.of());

        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0)).containsEntry("id", 1).containsEntry("description", "buy milk");
        assertThat(tasks.get(1)).containsEntry("description", "call mom");
    }

    // ----- exportJson: empty / blank / null output -----

    @Test
    void should_return_empty_list_when_output_is_empty() {
        doReturn(new TaskResult("", 0)).when(spyService).run(anyList(), any(), any());
        assertThat(spyService.exportJson(List.of(), Map.of())).isEmpty();
    }

    @Test
    void should_return_empty_list_when_output_is_blank() {
        doReturn(new TaskResult("   \n  ", 0)).when(spyService).run(anyList(), any(), any());
        assertThat(spyService.exportJson(List.of(), Map.of())).isEmpty();
    }

    @Test
    void should_return_empty_list_when_output_is_null() {
        doReturn(new TaskResult(null, 0)).when(spyService).run(anyList(), any(), any());
        assertThat(spyService.exportJson(List.of(), Map.of())).isEmpty();
    }

    // ----- exportJson: error paths -----

    @Test
    void should_throw_when_export_exit_code_is_nonzero() {
        doReturn(new TaskResult("some error", 2)).when(spyService).run(anyList(), any(), any());

        assertThatThrownBy(() -> spyService.exportJson(List.of("bogusfilter"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("export failed")
                .hasMessageContaining("2");
    }

    @Test
    void should_throw_when_output_is_not_valid_json() {
        doReturn(new TaskResult("not-json{", 0)).when(spyService).run(anyList(), any(), any());

        assertThatThrownBy(() -> spyService.exportJson(List.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse");
    }

    // ----- exportJson: routing / argument construction -----

    @Test
    @SuppressWarnings("unchecked")
    void should_append_export_after_filter_tokens_and_pass_null_timeout() {
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(new TaskResult("[]", 0)).when(spyService).run(argsCaptor.capture(), any(), isNull());

        spyService.exportJson(List.of("status:pending", "project:Home"), Map.of());

        assertThat(argsCaptor.getValue()).containsExactly("status:pending", "project:Home", "export");
        verify(spyService).run(any(), any(), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_only_pass_export_when_filter_tokens_empty() {
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(new TaskResult("[]", 0)).when(spyService).run(argsCaptor.capture(), any(), any());

        spyService.exportJson(List.of(), Map.of());

        assertThat(argsCaptor.getValue()).containsExactly("export");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_preserve_special_characters_in_filter_tokens() {
        ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
        doReturn(new TaskResult("[]", 0)).when(spyService).run(argsCaptor.capture(), any(), any());

        List<String> filters = List.of("description.contains:foo bar", "+tag", "project:Uber/Omega");
        spyService.exportJson(filters, Map.of());

        assertThat(argsCaptor.getValue())
                .containsExactly("description.contains:foo bar", "+tag", "project:Uber/Omega", "export");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_forward_env_map_to_run() {
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        doReturn(new TaskResult("[]", 0)).when(spyService).run(anyList(), envCaptor.capture(), any());

        Map<String, String> env = Map.of("TASKDATA", "/tmp/tasks", "TASKRC", "/tmp/taskrc");
        spyService.exportJson(List.of("status:pending"), env);

        assertThat(envCaptor.getValue())
                .containsEntry("TASKDATA", "/tmp/tasks")
                .containsEntry("TASKRC", "/tmp/taskrc");
    }

    // ----- exportJson: idempotency on repeated calls -----

    @Test
    void should_return_equal_results_and_invoke_run_each_time_on_repeated_calls() {
        String json = "[{\"id\":1}]";
        doReturn(new TaskResult(json, 0)).when(spyService).run(anyList(), any(), any());

        List<Map<String, Object>> first = spyService.exportJson(List.of("status:pending"), Map.of());
        List<Map<String, Object>> second = spyService.exportJson(List.of("status:pending"), Map.of());

        assertThat(first).isEqualTo(second);
        verify(spyService, times(2)).run(anyList(), any(), any());
    }
}
