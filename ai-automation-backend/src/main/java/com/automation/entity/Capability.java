package com.automation.entity;

import com.automation.util.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "capabilities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Capability {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    private String handlerClass;

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> inputSchema = new HashMap<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> outputSchema = new HashMap<>();

    @Builder.Default
    private boolean async = false;

    /**
     * Whether this capability is allowed to write to the project filesystem
     * (e.g. test-generator, feature-implementer). When true, the workflow block
     * must explicitly opt in via {@code allow_writes: true} in its config; otherwise
     * the block fails fast and the host-CLI is invoked with a read-only tool whitelist.
     */
    @Builder.Default
    private boolean writes = false;
}
