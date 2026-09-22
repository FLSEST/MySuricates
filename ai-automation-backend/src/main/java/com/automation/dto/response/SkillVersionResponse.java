package com.automation.dto.response;

import com.automation.entity.SkillVersion;

import java.time.Instant;

/** One recorded change of a skill; the snapshot stays server-side. */
public record SkillVersionResponse(String id, String skillId, int versionNumber, String operation,
                                   String changeSource, String contentHash, Instant createdAt) {

    public static SkillVersionResponse from(SkillVersion v) {
        return new SkillVersionResponse(v.getId(), v.getSkillId(), v.getVersionNumber(), v.getOperation().name(),
                v.getChangeSource(), v.getContentHash(), v.getCreatedAt());
    }
}
