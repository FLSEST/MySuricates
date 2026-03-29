package com.automation.entity;

import com.automation.util.ChainRunEntryListConverter;
import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "chain_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChainRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "chain_id", nullable = false)
    private WorkflowChain chain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RunStatus status = RunStatus.PENDING;

    @Convert(converter = ChainRunEntryListConverter.class)
    @Column(name = "entry_runs", columnDefinition = "text")
    @Builder.Default
    private List<ChainRunEntry> entryRuns = new ArrayList<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "input_context", columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> inputContext = new HashMap<>();

    private Instant startedAt;
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(columnDefinition = "text")
    private String error;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "triggered_by_schedule_id")
    private String triggeredByScheduleId;
}
