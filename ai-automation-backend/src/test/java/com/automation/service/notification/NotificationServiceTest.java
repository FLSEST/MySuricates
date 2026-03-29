package com.automation.service.notification;

import com.automation.dto.request.CreateNotificationConfigRequest;
import com.automation.entity.NotificationConfig;
import com.automation.entity.NotificationConfig.ChannelType;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.NotificationConfigRepository;
import com.automation.service.tray.SystemTrayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationConfigRepository repository;

    @Mock
    private SystemTrayService systemTrayService;

    private NotificationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, objectMapper);
    }

    @Nested
    class CrudTests {

        @Test
        void findAll_delegatesToRepository() {
            NotificationConfig config = createConfig("c-1", ChannelType.WEBHOOK);
            when(repository.findAll()).thenReturn(List.of(config));

            List<NotificationConfig> result = service.findAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo("c-1");
        }

        @Test
        void findById_existing_returnsConfig() {
            NotificationConfig config = createConfig("c-1", ChannelType.WEBHOOK);
            when(repository.findById("c-1")).thenReturn(Optional.of(config));

            NotificationConfig result = service.findById("c-1");

            assertThat(result.getId()).isEqualTo("c-1");
        }

        @Test
        void findById_nonExistent_throwsResourceNotFoundException() {
            when(repository.findById("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById("unknown"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void create_validRequest_buildsAndSavesConfig() {
            var req = new CreateNotificationConfigRequest(
                    "Slack", ChannelType.WEBHOOK, "https://hooks.slack.com/test",
                    null, true, true, false, null);
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            NotificationConfig result = service.create(req);

            assertThat(result.getName()).isEqualTo("Slack");
            assertThat(result.getChannelType()).isEqualTo(ChannelType.WEBHOOK);
            assertThat(result.getWebhookUrl()).isEqualTo("https://hooks.slack.com/test");
            verify(repository).save(any());
        }

        @Test
        void enable_setsEnabledTrue() {
            NotificationConfig config = createConfig("c-1", ChannelType.WEBHOOK);
            config.setEnabled(false);
            when(repository.findById("c-1")).thenReturn(Optional.of(config));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            NotificationConfig result = service.enable("c-1");

            assertThat(result.isEnabled()).isTrue();
        }

        @Test
        void disable_setsEnabledFalse() {
            NotificationConfig config = createConfig("c-1", ChannelType.WEBHOOK);
            config.setEnabled(true);
            when(repository.findById("c-1")).thenReturn(Optional.of(config));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            NotificationConfig result = service.disable("c-1");

            assertThat(result.isEnabled()).isFalse();
        }

        @Test
        void delete_existing_deletesById() {
            when(repository.existsById("c-1")).thenReturn(true);

            service.delete("c-1");

            verify(repository).deleteById("c-1");
        }

        @Test
        void delete_nonExistent_throwsResourceNotFoundException() {
            when(repository.existsById("unknown")).thenReturn(false);

            assertThatThrownBy(() -> service.delete("unknown"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class ShouldFireTests {

        @Test
        void triggerAlways_firesForAnyStatus() {
            NotificationConfig config = createConfig("c-1", ChannelType.DESKTOP);
            config.setTriggerAlways(true);
            config.setTriggerOnSuccess(false);
            config.setTriggerOnFailure(false);
            ReflectionTestUtils.setField(service, "systemTrayService", systemTrayService);
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of(config));

            service.onWorkflowRunCompleted(createEvent(RunStatus.RUNNING));

            verify(systemTrayService).showNotification(any(), any(), anyBoolean());
        }

        @Test
        void completed_triggerOnSuccess_fires() {
            NotificationConfig config = createConfig("c-1", ChannelType.DESKTOP);
            config.setTriggerOnSuccess(true);
            config.setTriggerOnFailure(false);
            config.setTriggerAlways(false);
            ReflectionTestUtils.setField(service, "systemTrayService", systemTrayService);
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of(config));

            service.onWorkflowRunCompleted(createEvent(RunStatus.COMPLETED));

            verify(systemTrayService).showNotification(any(), any(), eq(false));
        }

        @Test
        void failed_triggerOnFailure_fires() {
            NotificationConfig config = createConfig("c-1", ChannelType.DESKTOP);
            config.setTriggerOnSuccess(false);
            config.setTriggerOnFailure(true);
            config.setTriggerAlways(false);
            ReflectionTestUtils.setField(service, "systemTrayService", systemTrayService);
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of(config));

            service.onWorkflowRunCompleted(createEvent(RunStatus.FAILED));

            verify(systemTrayService).showNotification(any(), any(), eq(true));
        }

        @Test
        void running_noTrigger_doesNotFire() {
            NotificationConfig config = createConfig("c-1", ChannelType.DESKTOP);
            config.setTriggerOnSuccess(true);
            config.setTriggerOnFailure(true);
            config.setTriggerAlways(false);
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of(config));

            service.onWorkflowRunCompleted(createEvent(RunStatus.RUNNING));

            verifyNoInteractions(systemTrayService);
        }
    }

    @Nested
    class TemplateTests {

        @Test
        void nullTemplate_generatesDefault_completed() {
            NotificationConfig config = createConfig("c-1", ChannelType.DESKTOP);
            config.setMessageTemplate(null);
            config.setTriggerAlways(true);
            ReflectionTestUtils.setField(service, "systemTrayService", systemTrayService);
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of(config));

            service.onWorkflowRunCompleted(createEvent(RunStatus.COMPLETED));

            ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
            verify(systemTrayService).showNotification(any(), msgCaptor.capture(), anyBoolean());
            assertThat(msgCaptor.getValue()).contains("Test Workflow").contains("COMPLETED");
        }

        @Test
        void templateWithVariables_substitutesCorrectly() {
            NotificationConfig config = createConfig("c-1", ChannelType.DESKTOP);
            config.setMessageTemplate("{{workflow_name}} finished: {{status}}");
            config.setTriggerAlways(true);
            ReflectionTestUtils.setField(service, "systemTrayService", systemTrayService);
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of(config));

            service.onWorkflowRunCompleted(createEvent(RunStatus.COMPLETED));

            ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
            verify(systemTrayService).showNotification(any(), msgCaptor.capture(), anyBoolean());
            assertThat(msgCaptor.getValue()).isEqualTo("Test Workflow finished: COMPLETED");
        }
    }

    @Nested
    class EventHandlerTests {

        @Test
        void noConfigs_doesNothing() {
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of());

            service.onWorkflowRunCompleted(createEvent(RunStatus.COMPLETED));

            verifyNoInteractions(systemTrayService);
        }

        @Test
        void desktopChannel_noTrayService_skips() {
            NotificationConfig config = createConfig("c-1", ChannelType.DESKTOP);
            config.setTriggerAlways(true);
            // systemTrayService is NOT injected (remains null)
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of(config));

            // Should not throw
            service.onWorkflowRunCompleted(createEvent(RunStatus.COMPLETED));

            verifyNoInteractions(systemTrayService);
        }

        @Test
        void webhookChannel_noUrl_logsWarning() {
            NotificationConfig config = createConfig("c-1", ChannelType.WEBHOOK);
            config.setWebhookUrl(null);
            config.setTriggerAlways(true);
            when(repository.findEnabledForWorkflow(any())).thenReturn(List.of(config));

            // Should not throw
            service.onWorkflowRunCompleted(createEvent(RunStatus.COMPLETED));
        }
    }

    // --- Helpers ---

    private NotificationConfig createConfig(String id, ChannelType channelType) {
        return NotificationConfig.builder()
                .id(id)
                .name("Test Config")
                .enabled(true)
                .channelType(channelType)
                .webhookUrl(channelType == ChannelType.WEBHOOK ? "https://hooks.slack.com/test" : null)
                .triggerOnSuccess(true)
                .triggerOnFailure(true)
                .triggerAlways(false)
                .build();
    }

    private WorkflowRunCompletedEvent createEvent(RunStatus status) {
        return new WorkflowRunCompletedEvent(
                this, "run-1", "wf-1", "Test Workflow",
                "proj-1", "/workspace", status,
                Map.of(), Map.of(), Map.of());
    }
}
