package com.automation.entity;

import com.automation.util.ChainEntryListConverter;
import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "workflow_chains")
@EntityListeners(AuditTimestampListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowChain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private String icon;

    @Convert(converter = ChainEntryListConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private List<ChainEntry> entries = new ArrayList<>();

    @Column(name = "stop_on_failure")
    @Builder.Default
    private boolean stopOnFailure = true;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @OneToMany(mappedBy = "chain", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ChainRun> runs = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
