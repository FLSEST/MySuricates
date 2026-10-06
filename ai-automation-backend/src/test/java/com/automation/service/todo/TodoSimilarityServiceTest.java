package com.automation.service.todo;

import com.automation.config.TodoSimilarityConfig;
import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoSimilarityServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private AiProviderRegistry aiProviderRegistry;

    private final TodoSimilarityConfig config = new TodoSimilarityConfig();

    private TodoSimilarityService service() {
        return new TodoSimilarityService(todoRepository, aiProviderRegistry, config);
    }

    private final TodoSimilarityService service = new TodoSimilarityService(
            mock(TodoRepository.class), mock(AiProviderRegistry.class), new TodoSimilarityConfig());

    @Test
    void extractKeywords_normalTitle() {
        Set<String> keywords = service.extractKeywords("WishService in WishPromptBuilder aufteilen");
        assertThat(keywords).contains("wishservice", "wishpromptbuilder", "aufteilen");
        assertThat(keywords).doesNotContain("in"); // stop word
    }

    @Test
    void extractKeywords_withPrefixes() {
        Set<String> keywords = service.extractKeywords("[Incident] MySuricates Architecture Review — quality");
        assertThat(keywords).contains("mysuricates", "architecture", "review", "quality");
        assertThat(keywords).doesNotContain("[incident]"); // stop word
    }

    @Test
    void extractKeywords_null() {
        assertThat(service.extractKeywords(null)).isEmpty();
    }

    @Test
    void extractKeywords_blank() {
        assertThat(service.extractKeywords("  ")).isEmpty();
    }

    @Test
    void extractKeywords_shortWordsFiltered() {
        Set<String> keywords = service.extractKeywords("Fix a bug in the UI");
        // "fix", "a", "bug", "in", "the", "UI" — all ≤3 chars or stop words
        assertThat(keywords).isEmpty();
    }

    @Test
    void jaccardSimilarity_identical() {
        Set<String> a = Set.of("wishservice", "aufteilen", "backend");
        assertThat(service.jaccardSimilarity(a, a)).isEqualTo(1.0);
    }

    @Test
    void jaccardSimilarity_noOverlap() {
        Set<String> a = Set.of("wishservice", "aufteilen");
        Set<String> b = Set.of("frontend", "testing");
        assertThat(service.jaccardSimilarity(a, b)).isEqualTo(0.0);
    }

    @Test
    void jaccardSimilarity_partialOverlap() {
        Set<String> a = Set.of("wishservice", "aufteilen", "backend");
        Set<String> b = Set.of("wishservice", "teilservices", "aufbrechen");
        // Intersection: {wishservice} = 1, Union: 5
        assertThat(service.jaccardSimilarity(a, b)).isEqualTo(0.2);
    }

    @Test
    void jaccardSimilarity_highOverlap() {
        Set<String> a = Set.of("assistantservice", "aufteilen", "contextbuilder");
        Set<String> b = Set.of("assistantservice", "aufteilen", "fetchservice", "toolexecutor");
        // Intersection: {assistantservice, aufteilen} = 2, Union: 5
        assertThat(service.jaccardSimilarity(a, b)).isEqualTo(0.4);
    }

    @Test
    void jaccardSimilarity_emptyBoth() {
        assertThat(service.jaccardSimilarity(Set.of(), Set.of())).isEqualTo(0.0);
    }

    /**
     * The threshold lives in the settings and is read per call: a change takes effect
     * without restart (TODO 380818b1). Candidate and title overlap at 0.2.
     */
    @Test
    void findSimilar_readsTheKeywordThresholdPerCall() throws Exception {
        Todo candidate = Todo.builder().id("t-1")
                .title("WishService in Teilservices aufbrechen").build();
        when(todoRepository.findByProjectIdAndStatusIn(any(), any())).thenReturn(List.of(candidate));
        AiProvider provider = mock(AiProvider.class);
        when(aiProviderRegistry.getDefault()).thenReturn(provider);
        when(provider.reason(any())).thenReturn(new AiProvider.ReasoningResult("SAME", null));
        TodoSimilarityService service = service();
        String title = "WishService in WishPromptBuilder aufteilen";

        config.setKeywordThreshold(0.2);
        assertThat(service.findSimilar("p-1", title, null)).hasSize(1);

        config.setKeywordThreshold(0.9);
        assertThat(service.findSimilar("p-1", title, null))
                .as("hoehere Schwelle: derselbe Kandidat faellt raus, ohne Neustart").isEmpty();
    }

    @Test
    void keywordSimilarity_realTitles() {
        double score1 = service.keywordSimilarity(
                "WishService in WishPromptBuilder aufteilen",
                "WishService (1489 LOC) in Teilservices aufbrechen");
        // "wishservice" overlaps → some score
        assertThat(score1).isGreaterThan(0.0);

        double score2 = service.keywordSimilarity(
                "WishService aufteilen",
                "Frontend Bundle optimieren");
        assertThat(score2).isEqualTo(0.0);
    }
}
