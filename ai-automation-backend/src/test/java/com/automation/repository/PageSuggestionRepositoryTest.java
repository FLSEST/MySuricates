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

        Optional<PageSuggestion> result = repository.findByPageAndLocale("dashboard", "de");

        assertThat(result).isPresent();
        assertThat(result.get().getPage()).isEqualTo("dashboard");
        assertThat(result.get().getSuggestions()).isEqualTo("[\"a\",\"b\"]");
        assertThat(result.get().getGeneratedBy()).isEqualTo("CLAUDE");
        assertThat(result.get().getId()).isNotBlank();
        assertThat(result.get().getGeneratedAt()).isNotNull();
    }

    @Test
    void should_returnEmpty_when_pageDoesNotExist() {
        Optional<PageSuggestion> result = repository.findByPageAndLocale("non-existent", "de");

        assertThat(result).isEmpty();
    }

    @Test
    void should_returnEmpty_when_pageIsNull() {
        Optional<PageSuggestion> result = repository.findByPageAndLocale(null, "de");

        assertThat(result).isEmpty();
    }

    @Test
    void should_distinguishBetweenPages_when_multipleSuggestionsExist() {
        repository.save(PageSuggestion.builder().page("workflows").suggestions("[\"x\"]").build());
        repository.save(PageSuggestion.builder().page("chains").suggestions("[\"y\"]").build());

        Optional<PageSuggestion> workflows = repository.findByPageAndLocale("workflows", "de");
        Optional<PageSuggestion> chains = repository.findByPageAndLocale("chains", "de");

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

    /** 5157fac2: one set per page and language – the English UI no longer reads the German set. */
    @Test
    void should_keepOneSetPerLanguage_when_samePageHasGermanAndEnglish() {
        repository.save(PageSuggestion.builder().page("run").suggestions("[\"Erkläre diesen Run\"]").build());
        repository.save(PageSuggestion.builder().page("run").locale("en").suggestions("[\"Explain this run\"]").build());

        assertThat(repository.findByPageAndLocale("run", "de")).get()
                .extracting(PageSuggestion::getSuggestions).isEqualTo("[\"Erkläre diesen Run\"]");
        assertThat(repository.findByPageAndLocale("run", "en")).get()
                .extracting(PageSuggestion::getSuggestions).isEqualTo("[\"Explain this run\"]");
    }
}
