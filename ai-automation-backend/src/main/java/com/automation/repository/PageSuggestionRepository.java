package com.automation.repository;

import com.automation.entity.PageSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PageSuggestionRepository extends JpaRepository<PageSuggestion, String> {

    Optional<PageSuggestion> findByPageAndLocale(String page, String locale);
}
