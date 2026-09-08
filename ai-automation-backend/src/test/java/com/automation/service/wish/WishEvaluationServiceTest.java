package com.automation.service.wish;

import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WishEvaluationServiceTest {

    @Mock private WishPromptBuilder promptBuilder;
    @Mock private AiProviderRegistry aiProviderRegistry;
    @Mock private WishRepository wishRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private AiProvider provider;

    private WishEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new WishEvaluationService(promptBuilder, aiProviderRegistry, wishRepository, messagingTemplate);
        when(promptBuilder.buildEvaluatePrompt(any())).thenReturn("EVAL PROMPT");
        when(aiProviderRegistry.getDefault()).thenReturn(provider);
    }

    private void aiReturns(String... texts) throws Exception {
        var stub = when(provider.reason(any()));
        for (String t : texts) stub = stub.thenReturn(new AiProvider.ReasoningResult(t, null));
    }

    private Wish wish(int iteration, int max, boolean autoRefine) {
        Wish w = Wish.builder().id("w-1").originalText("x").status(WishStatus.EVALUATING)
                .iterationCount(iteration).maxIterations(max).autoRefine(autoRefine)
                .metadata(new HashMap<>()).iterationHistory(new ArrayList<>()).build();
        return w;
    }

    @Test
    void satisfied_withoutAutoRefine_awaitsApproval_andStoresEvaluation() throws Exception {
        aiReturns("{\"satisfied\":true,\"summary\":\"All good\",\"issues\":[]}");
        Wish w = wish(1, 5, false);

        service.evaluate(w);

        assertThat(w.getStatus()).isEqualTo(WishStatus.AWAITING_APPROVAL);
        assertThat(w.getResultSummary()).isEqualTo("All good");
        assertThat((List<?>) w.getMetadata().get("evaluations")).hasSize(1);
        assertThat(w.getMetadata()).containsKey("pendingEvaluation");
        verify(wishRepository).save(w);
    }

    @Test
    void satisfied_withAutoRefine_completes() throws Exception {
        aiReturns("{\"satisfied\":true,\"summary\":\"done\"}");
        Wish w = wish(1, 5, true);

        service.evaluate(w);

        assertThat(w.getStatus()).isEqualTo(WishStatus.COMPLETED);
    }

    @Test
    void notSatisfied_shouldRetry_belowMax_refines() throws Exception {
        aiReturns("{\"satisfied\":false,\"shouldRetry\":true,\"summary\":\"try again\",\"issues\":[\"a\"]}");
        Wish w = wish(2, 5, false);

        service.evaluate(w);

        assertThat(w.getStatus()).isEqualTo(WishStatus.REFINING);
        assertThat(w.getResultSummary()).isEqualTo("try again");
        assertThat(w.getError()).isNull();
        verify(wishRepository).save(w);
    }

    @Test
    void notSatisfied_shouldRetry_atMax_fails() throws Exception {
        aiReturns("{\"satisfied\":false,\"shouldRetry\":true,\"summary\":\"nope\",\"issues\":[\"a\",\"b\"]}");
        Wish w = wish(5, 5, false);

        service.evaluate(w);

        assertThat(w.getStatus()).isEqualTo(WishStatus.FAILED);
        assertThat(w.getError()).isEqualTo("Not satisfied after 5 iterations. Issues: [a, b]");
    }

    @Test
    void notSatisfied_noRetry_fails() throws Exception {
        aiReturns("{\"satisfied\":false,\"shouldRetry\":false,\"summary\":\"bad\"}");
        Wish w = wish(1, 5, false);

        service.evaluate(w);

        assertThat(w.getStatus()).isEqualTo(WishStatus.FAILED);
        assertThat(w.getError()).startsWith("Not satisfied after 1 iterations.");
    }

    @Test
    void emptyEvaluation_isTreatedAsRetry_andFlagged() throws Exception {
        aiReturns("{}");
        Wish w = wish(1, 5, false);

        service.evaluate(w);

        assertThat(w.getStatus()).isEqualTo(WishStatus.REFINING);
        assertThat(w.getResultSummary()).isEqualTo("Evaluation returned empty response (CLI failure). Automatic retry.");
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) ((List<?>) w.getMetadata().get("evaluations")).get(0);
        assertThat(stored).containsEntry("_emptyEvaluation", true).containsEntry("shouldRetry", true);
    }

    @Test
    void satisfiedNull_withSummaryAndNoIssues_isInferredSatisfied() throws Exception {
        aiReturns("{\"summary\":\"Looks fine\"}");
        Wish w = wish(1, 5, false);

        service.evaluate(w);

        assertThat(w.getStatus()).isEqualTo(WishStatus.AWAITING_APPROVAL);
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) ((List<?>) w.getMetadata().get("evaluations")).get(0);
        assertThat(stored).containsEntry("_inferredSatisfied", true).containsEntry("satisfied", true);
    }

    @Test
    void invalidJson_retriesWithShorterPromptInstruction() throws Exception {
        aiReturns("definitely not json", "{\"satisfied\":true,\"summary\":\"ok\"}");
        Wish w = wish(1, 5, false);

        service.evaluate(w);

        ArgumentCaptor<AiProvider.ReasoningRequest> req = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
        verify(provider, times(2)).reason(req.capture());
        assertThat(req.getAllValues().get(1).prompt())
                .startsWith("EVAL PROMPT")
                .contains("IMPORTANT: Keep response under 500 tokens.");
        assertThat(w.getStatus()).isEqualTo(WishStatus.AWAITING_APPROVAL);
    }

    @Test
    void evaluation_isAppendedToExistingEvaluations_andCurrentIterationEntry() throws Exception {
        aiReturns("{\"satisfied\":true,\"summary\":\"ok\"}");
        Wish w = wish(2, 5, false);
        w.getMetadata().put("evaluations", new ArrayList<>(List.of(Map.of("old", true))));
        w.setIterationHistory(new ArrayList<>(List.of(new HashMap<>(Map.of("iteration", 1)), new HashMap<>(Map.of("iteration", 2)))));

        service.evaluate(w);

        assertThat((List<?>) w.getMetadata().get("evaluations")).hasSize(2);
        assertThat(w.getIterationHistory().get(1)).containsKey("evaluation");
        assertThat(w.getIterationHistory().get(0)).doesNotContainKey("evaluation");
    }
}
