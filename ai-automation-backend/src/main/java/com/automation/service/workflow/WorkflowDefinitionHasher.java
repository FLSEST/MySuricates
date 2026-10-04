package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.Workflow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * SHA-256 over the canonical JSON of a workflow's executable definition ({@link WorkflowSnapshot}:
 * blocks, instructions, model, provider, metadata, default context, activated skills, effort).
 * Labels such as name, description, icon or tags are not part of the hash.
 *
 * <p>Canonical form: properties and map keys sorted, empty values (null, "", [], {}) omitted so that
 * null and empty are the same definition, activated skill IDs sorted (their persistence order is not
 * meaningful), block order kept (it is). A private mapper is used on purpose so Spring's Jackson
 * customisation can never change a hash. {@code WorkflowDefinitionHasherTest} pins a known vector.
 */
@Component
public class WorkflowDefinitionHasher {

    /** Shared with {@link DefinitionIntegrity}, so an import checksum is canonicalised exactly like this hash. */
    static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .serializationInclusion(JsonInclude.Include.NON_EMPTY)
            .build();

    public String hash(Workflow workflow) {
        return hash(WorkflowSnapshot.from(workflow));
    }

    public String hash(WorkflowSnapshot snapshot) {
        return sha256Hex(canonicalJson(snapshot));
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of {@code text}. */
    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    String canonicalJson(WorkflowSnapshot snapshot) {
        List<String> skillIds = snapshot.activatedSkillIds() == null ? List.of()
                : snapshot.activatedSkillIds().stream().sorted().toList();
        WorkflowSnapshot normalized = new WorkflowSnapshot(snapshot.blocks(), snapshot.instructions(), snapshot.model(),
                snapshot.provider(), snapshot.metadata(), snapshot.defaultContext(), skillIds, snapshot.effort());
        try {
            return CANONICAL.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Workflow definition is not serialisable: " + e.getOriginalMessage(), e);
        }
    }
}
