package com.automation.config;

/**
 * Semver comparison for the seeders of shipped definitions
 * ({@link WorkflowTemplateSeeder}, {@link SkillSeeder}).
 */
final class SeedVersions {

    private SeedVersions() {}

    /** {@code true} if {@code newVersion} is greater than {@code oldVersion}. */
    static boolean isNewer(String newVersion, String oldVersion) {
        if (oldVersion == null || oldVersion.isBlank()) return true;
        if (newVersion == null || newVersion.isBlank()) return false;

        String[] newParts = newVersion.split("\\.");
        String[] oldParts = oldVersion.split("\\.");

        for (int i = 0; i < Math.max(newParts.length, oldParts.length); i++) {
            int n = i < newParts.length ? Integer.parseInt(newParts[i]) : 0;
            int o = i < oldParts.length ? Integer.parseInt(oldParts[i]) : 0;
            if (n > o) return true;
            if (n < o) return false;
        }
        return false;
    }
}
