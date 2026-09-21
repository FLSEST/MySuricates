package com.automation.entity;

import com.automation.util.BlockDefinitionListConverter;
import com.automation.util.JsonStringMapConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_templates")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTemplate {

    @Id
    private String id;

    @PrePersist
    private void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    @Convert(converter = JsonStringMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    @Builder.Default
    private Map<String, String> name = new HashMap<>();

    @Convert(converter = JsonStringMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, String> description = new HashMap<>();

    private String icon;

    private String category;

    @Convert(converter = BlockDefinitionListConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    @Builder.Default
    private List<BlockDefinition> blocks = new ArrayList<>();

    @Column(columnDefinition = "text")
    private String instructions;

    /**
     * Unresolved {@code blocks}/{@code instructions} of a bilingual template, with its {@code i18n}
     * marker ({@link com.automation.util.LocalizedJson#localizedSource}). {@code blocks} and
     * {@code instructions} above hold the English variant; the other language is resolved from here
     * when read. {@code null} for single-language templates.
     */
    @Column(name = "i18n_source", columnDefinition = "text")
    private String i18nSource;

    private String model;

    @Builder.Default
    private boolean builtIn = false;

    @Builder.Default
    private int sortOrder = 0;

    @Builder.Default
    private String qualityStatus = "UNTESTED";

    @Builder.Default
    private String version = "1.0.0";

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
