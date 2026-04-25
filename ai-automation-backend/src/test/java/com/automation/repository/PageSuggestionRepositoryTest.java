package com.automation.repository;

import com.automation.entity.PageSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class PageSuggestionRepositoryTest {

    @Autowired
    private PageSuggestionRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void should_returnSuggestion_when_pageExists() {
        PageSuggestion entity = PageSuggestion.builder()
                .page("dashboard")
                .suggestions("[\"a\",\"b\"]")
                .build();
        repository.save(entity);

        Optional<PageSuggestion> result = repository.findByPage("dashboard");

        assertThat(result).isPresent();
        assertThat(result.get().getPage()).isEqualTo("dashboard");
        assertThat(result.get().getSuggestions()).isEqualTo("[\"a\",\"b\"]");
        assertThat(result.get().getGeneratedBy()).isEqualTo("CLAUDE");
        assertThat(result.get().getId()).isNotBlank();
        assertThat(result.get().getGeneratedAt()).isNotNull();
    }

    @Test
    void should_returnEmpty_when_pageDoesNotExist() {
        Optional<PageSuggestion> result = repository.findByPage("non-existent");

        assertThat(result).isEmpty();
    }

    @Test
    void should_returnEmpty_when_pageIsNull() {
        Optional<PageSuggestion> result = repository.findByPage(null);

        assertThat(result).isEmpty();
    }

    @Test
    void should_distinguishBetweenPages_when_multipleSuggestionsExist() {
        repository.save(PageSuggestion.builder().page("workflows").suggestions("[\"x\"]").build());
        repository.save(PageSuggestion.builder().page("chains").suggestions("[\"y\"]").build());

        Optional<PageSuggestion> workflows = repository.findByPage("workflows");
        Optional<PageSuggestion> chains = repository.findByPage("chains");

        assertThat(workflows).isPresent();
        assertThat(workflows.get().getSuggestions()).isEqualTo("[\"x\"]");
        assertThat(chains).isPresent();
        assertThat(chains.get().getSuggestions()).isEqualTo("[\"y\"]");
    }

    @Test
    void should_persistAndRetrieve_when_saveCalled() {
        PageSuggestion entity = PageSuggestion.builder()
                .page("settings")
                .suggestions("[]")
                .basedOnSessions(5)
                .build();

        PageSuggestion saved = repository.save(entity);

        assertThat(saved.getId()).isNotBlank();
        assertThat(repository.findById(saved.getId())).isPresent();
    }
}
