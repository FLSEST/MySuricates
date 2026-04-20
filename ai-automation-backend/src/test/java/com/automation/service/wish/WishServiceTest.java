package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.dto.request.WishRespondRequest;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.learning.LearningService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishServiceTest {

    @Mock private WishRepository wishRepository;
    @Mock private AiProviderRegistry aiProviderRegistry;
    @Mock private WorkflowService workflowService;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowTemplateService workflowTemplateService;
    @Mock private LearningService learningService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ExecutorService virtualThreadExecutor;
    @Mock private WishPromptBuilder promptBuilder;
    @Mock private WishDecomposeService decomposeService;
    @Mock private WishExecutionService executionService;

    private WishService wishService;

    @BeforeEach
    void setUp() {
        WishConfig wishConfig = new WishConfig();
        wishService = new WishService(
                wishConfig,
                wishRepository, aiProviderRegistry, workflowService,
                projectRepository, workflowTemplateService, learningService,
                messagingTemplate, virtualThreadExecutor,
                promptBuilder, decomposeService, executionService
        );
    }

    // --- CRUD ---

    @Nested
    class CrudTests {

        @Test
        void findAll_returnsAllWishes() {
            Wish w1 = createWish("w-1", WishStatus.RECEIVED);
            Wish w2 = createWish("w-2", WishStatus.COMPLETED);
            when(wishRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(w2, w1));

            List<Wish> result = wishService.findAll();

            assertThat(result).hasSize(2);
        }

        @Test
        void findById_existing_returnsWish() {
            Wish wish = createWish("w-1", WishStatus.RECEIVED);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            Wish result = wishService.findById("w-1");

            assertThat(result.getId()).isEqualTo("w-1");
        }

        @Test
        void findById_nonExistent_throwsException() {
            when(wishRepository.findById("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> wishService.findById("unknown"))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        void create_withDefaults_setsMaxIterationsTo5() {
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(new Project()));
            when(wishRepository.save(any(Wish.class))).thenAnswer(inv -> {
                Wish w = inv.getArgument(0);
                w.setId("w-new");
                return w;
            });

            Wish result = wishService.create("Build a dashboard", "proj-1", null, null);

            assertThat(result.getOriginalText()).isEqualTo("Build a dashboard");
            assertThat(result.getMaxIterations()).isEqualTo(5);
            assertThat(result.getStatus()).isEqualTo(WishStatus.RECEIVED);
        }

        @Test
        void create_withCustomMaxIterations_usesProvided() {
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(new Project()));
            when(wishRepository.save(any(Wish.class))).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.create("Test", "proj-1", 10, null);

            assertThat(result.getMaxIterations()).isEqualTo(10);
        }

        @Test
        void create_withNullProjectId_throwsException() {
            assertThatThrownBy(() -> wishService.create("Test", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("projectId is required");
        }

        @Test
        void create_withBlankProjectId_throwsException() {
            assertThatThrownBy(() -> wishService.create("Test", "  ", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("projectId is required");
        }

        @Test
        void create_withInvalidProject_throwsException() {
            when(projectRepository.findById("bad-project")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> wishService.create("Test", "bad-project", null, null))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Project not found");
        }

        @Test
        void create_withValidProject_succeeds() {
            Project project = Project.builder().id("p-1").name("MyProject").build();
            when(projectRepository.findById("p-1")).thenReturn(Optional.of(project));
            when(wishRepository.save(any(Wish.class))).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.create("Test", "p-1", null, null);

            assertThat(result.getProjectId()).isEqualTo("p-1");
        }

        @Test
        void create_defaultsAutoRefineToFalse_whenNotProvided() {
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(new Project()));
            when(wishRepository.save(any(Wish.class))).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.create("Test", "proj-1", null, null);

            assertThat(result.isAutoRefine()).isFalse();
        }

        @Test
        void create_defaultsAutoRefineToFalse_whenNullExplicitly() {
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(new Project()));
            when(wishRepository.save(any(Wish.class))).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.create("Test", "proj-1", null, null, null);

            assertThat(result.isAutoRefine()).isFalse();
        }

        @Test
        void create_setsAutoRefineTrue_whenRequested() {
            when(projectRepository.findById("proj-1")).thenReturn(Optional.of(new Project()));
            when(wishRepository.save(any(Wish.class))).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.create("Test", "proj-1", null, null, Boolean.TRUE);

            assertThat(result.isAutoRefine()).isTrue();
        }
    }

    // --- Delete ---

    @Nested
    class DeleteTests {

        @Test
        void delete_completedWish_succeeds() {
            Wish wish = createWish("w-1", WishStatus.COMPLETED);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            wishService.delete("w-1");

            verify(wishRepository).delete(wish);
        }

        @Test
        void delete_failedWish_succeeds() {
            Wish wish = createWish("w-1", WishStatus.FAILED);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            wishService.delete("w-1");

            verify(wishRepository).delete(wish);
        }

        @Test
        void delete_cancelledWish_succeeds() {
            Wish wish = createWish("w-1", WishStatus.CANCELLED);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            wishService.delete("w-1");

            verify(wishRepository).delete(wish);
        }

        @Test
        void delete_activeWish_throwsException() {
            Wish wish = createWish("w-1", WishStatus.EXECUTING);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.delete("w-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal state");
        }

        @Test
        void delete_receivedWish_throwsException() {
            Wish wish = createWish("w-1", WishStatus.RECEIVED);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.delete("w-1"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void delete_awaitingDecomposeApprovalWish_throwsException() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_DECOMPOSE_APPROVAL);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.delete("w-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal state");
        }
    }

    // --- Respond ---

    @Nested
    class RespondTests {

        @Test
        void respond_accept_fromAwaitingApproval_completesWish() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_APPROVAL);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.ACCEPT, null));

            assertThat(result.getStatus()).isEqualTo(WishStatus.COMPLETED);
        }

        @Test
        void respond_accept_fromWrongState_throwsException() {
            Wish wish = createWish("w-1", WishStatus.EXECUTING);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.ACCEPT, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AWAITING_APPROVAL");
        }

        @Test
        void respond_refine_fromAwaitingApproval_setsRefiningAndTriggersAsync() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_APPROVAL);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.REFINE, "Add more tests"));

            assertThat(result.getStatus()).isEqualTo(WishStatus.REFINING);
            assertThat(result.getMetadata()).containsEntry("userFeedback", "Add more tests");
            verify(virtualThreadExecutor).submit(any(Runnable.class));
        }

        @Test
        void respond_refine_fromWrongState_throwsException() {
            Wish wish = createWish("w-1", WishStatus.RECEIVED);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.REFINE, "feedback")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void respond_cancel_fromAwaitingApproval_cancels() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_APPROVAL);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.CANCEL, null));

            assertThat(result.getStatus()).isEqualTo(WishStatus.CANCELLED);
        }

        @Test
        void respond_cancel_fromAwaitingClarification_cancels() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_CLARIFICATION);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.CANCEL, null));

            assertThat(result.getStatus()).isEqualTo(WishStatus.CANCELLED);
        }

        @Test
        void respond_cancel_fromNonPausedState_throwsException() {
            Wish wish = createWish("w-1", WishStatus.EXECUTING);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.CANCEL, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AWAITING_*");
        }

        @Test
        void respond_clarify_storesQAAndResetsToReceived() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_CLARIFICATION);
            wish.setMetadata(new HashMap<>(Map.of("clarificationQuestion", "What framework?")));
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.CLARIFY, "React"));

            assertThat(result.getStatus()).isEqualTo(WishStatus.RECEIVED);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> clarifications =
                    (List<Map<String, String>>) result.getMetadata().get("clarifications");
            assertThat(clarifications).hasSize(1);
            assertThat(clarifications.get(0)).containsEntry("question", "What framework?");
            assertThat(clarifications.get(0)).containsEntry("answer", "React");
            assertThat(result.getMetadata()).doesNotContainKey("clarificationQuestion");
            verify(virtualThreadExecutor).submit(any(Runnable.class));
        }

        @Test
        void respond_clarify_fromWrongState_throwsException() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_APPROVAL);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.CLARIFY, "answer")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AWAITING_CLARIFICATION");
        }

        // --- Decompose Approval ---

        @Test
        void respond_accept_fromAwaitingDecomposeApproval_startsExecution() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_DECOMPOSE_APPROVAL);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.ACCEPT, null));

            assertThat(result.getStatus()).isEqualTo(WishStatus.EXECUTING);
            verify(virtualThreadExecutor).submit(any(Runnable.class));
        }

        @Test
        void respond_refine_fromAwaitingDecomposeApproval_triggersRedecompose() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_DECOMPOSE_APPROVAL);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.REFINE, "Use fewer blocks"));

            assertThat(result.getStatus()).isEqualTo(WishStatus.REFINING);
            assertThat(result.getMetadata()).containsEntry("decomposeFeedback", "Use fewer blocks");
            verify(virtualThreadExecutor).submit(any(Runnable.class));
        }

        @Test
        void respond_cancel_fromAwaitingDecomposeApproval_cancels() {
            Wish wish = createWish("w-1", WishStatus.AWAITING_DECOMPOSE_APPROVAL);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Wish result = wishService.respond("w-1",
                    new WishRespondRequest(WishRespondRequest.WishResponseAction.CANCEL, null));

            assertThat(result.getStatus()).isEqualTo(WishStatus.CANCELLED);
        }
    }

    // --- Crystallize ---

    @Nested
    class CrystallizeTests {

        @Test
        void crystallize_completedWish_createsTemplate() {
            Wish wish = createWish("w-1", WishStatus.COMPLETED);
            wish.setGeneratedWorkflowIds(List.of("wf-gen-1"));
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowTemplate template = WorkflowTemplate.builder().id("tmpl-1").build();
            when(workflowTemplateService.createFromWorkflow("wf-gen-1")).thenReturn(template);

            String templateId = wishService.crystallize("w-1");

            assertThat(templateId).isEqualTo("tmpl-1");
            assertThat(wish.getMetadata()).containsEntry("crystallizedTemplateId", "tmpl-1");
        }

        @Test
        void crystallize_nonCompletedWish_throwsException() {
            Wish wish = createWish("w-1", WishStatus.EXECUTING);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.crystallize("w-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completed");
        }

        @Test
        void crystallize_noWorkflows_throwsException() {
            Wish wish = createWish("w-1", WishStatus.COMPLETED);
            wish.setGeneratedWorkflowIds(List.of());
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            assertThatThrownBy(() -> wishService.crystallize("w-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No generated workflows");
        }
    }

    // --- Cancel ---

    @Nested
    class CancelTests {

        @Test
        void cancel_activeWish_setsCancelledStatus() {
            Wish wish = createWish("w-1", WishStatus.EXECUTING);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            wishService.cancel("w-1");

            assertThat(wish.getStatus()).isEqualTo(WishStatus.CANCELLED);
            verify(wishRepository).save(wish);
        }

        @Test
        void cancel_alreadyTerminal_doesNotSave() {
            Wish wish = createWish("w-1", WishStatus.COMPLETED);
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            wishService.cancel("w-1");

            verify(wishRepository, never()).save(any());
        }
    }

    // --- Helpers ---

    private Wish createWish(String id, WishStatus status) {
        Wish wish = Wish.builder()
                .id(id)
                .originalText("Test wish")
                .status(status)
                .metadata(new HashMap<>())
                .generatedWorkflowIds(new ArrayList<>())
                .build();
        return wish;
    }
}
