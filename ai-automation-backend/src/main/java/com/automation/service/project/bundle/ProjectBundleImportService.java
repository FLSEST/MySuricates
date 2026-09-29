package com.automation.service.project.bundle;

import com.automation.dto.bundle.BundleImportRequest;
import com.automation.dto.bundle.BundleImportRequest.TargetProject;
import com.automation.dto.bundle.BundleImportResult;
import com.automation.dto.bundle.ProjectBundle;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Skill;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.SkillRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code POST /api/v1/projects/import}: validates the bundle and the request, replaces the path tokens
 * with the user's folders, then writes everything in ONE transaction ({@link BundleImportWriter}) —
 * only database work inside, nothing slow. The activity entry follows after the commit, so a failed
 * import leaves no trace but the 4xx.
 *
 * <p>The API does not insist on a preview first: it is behind the API key like every other write.
 * The UI shows the preview (including the shell commands) before it offers the import button.
 */
@Service
public class ProjectBundleImportService {

    static final String MODE_NEW = "new";
    static final String MODE_EXISTING = "existing";
    static final String ON_CONFLICT_SKIP = "skip";
    static final String ON_CONFLICT_RENAME = "rename";

    private final BundleRequestReader reader;
    private final BundleImportWriter writer;
    private final SkillRepository skillRepository;
    private final ModuleRegistry modules;
    private final ActivityLogService activityLogService;
    private final TransactionTemplate transactionTemplate;

    public ProjectBundleImportService(BundleRequestReader reader, BundleImportWriter writer,
                                      SkillRepository skillRepository, ModuleRegistry modules,
                                      ActivityLogService activityLogService,
                                      PlatformTransactionManager transactionManager) {
        this.reader = reader;
        this.writer = writer;
        this.skillRepository = skillRepository;
        this.modules = modules;
        this.activityLogService = activityLogService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public BundleImportResult importBundle(String body) {
        BundleImportRequest request = reader.read(body, BundleImportRequest.class);
        BundleValidator.validate(request.bundle());
        TargetProject target = checkTarget(request.project());
        boolean rename = checkOnConflict(request.onConflict());
        ProjectBundle bundle = BundlePathMapping.apply(request.bundle(), request.pathMappings());
        boolean chainsEnabled = modules.isEnabled(ModuleCatalog.CHAINS);

        BundleImportResult result = transactionTemplate.execute(status -> {
            Map<String, String> skillIdByName = skillRepository.findAll().stream()
                    .collect(Collectors.toMap(Skill::getName, Skill::getId, (a, b) -> a));
            Set<String> warnings = new LinkedHashSet<>(
                    BundleWarnings.of(bundle, skillIdByName.keySet(), chainsEnabled));
            return writer.write(bundle, target, rename, skillIdByName, chainsEnabled, warnings);
        });

        activityLogService.info(LogCategory.SYSTEM, "Project imported from bundle: "
                        + result.created().workflows() + " workflows, " + result.created().chains() + " chains, "
                        + result.created().schedules() + " schedules (disabled), " + result.skipped().size() + " skipped",
                "ProjectBundleImportService", result.projectId());
        return result;
    }

    private static TargetProject checkTarget(TargetProject target) {
        if (target == null || target.mode() == null) {
            throw new IllegalArgumentException("project.mode is required ('new' or 'existing')");
        }
        String mode = target.mode().strip().toLowerCase(Locale.ROOT);
        if (MODE_EXISTING.equals(mode)) {
            if (target.projectId() == null || target.projectId().isBlank()) {
                throw new IllegalArgumentException("project.projectId is required for mode 'existing'");
            }
        } else if (!MODE_NEW.equals(mode)) {
            throw new IllegalArgumentException("project.mode must be 'new' or 'existing', got '" + target.mode() + "'");
        }
        return new TargetProject(mode, target.name(), target.path(), target.projectId());
    }

    /** @return true for {@code rename}; {@code skip} is the default */
    private static boolean checkOnConflict(String onConflict) {
        if (onConflict == null || onConflict.isBlank()) {
            return false;
        }
        String value = onConflict.strip().toLowerCase(Locale.ROOT);
        if (!List.of(ON_CONFLICT_SKIP, ON_CONFLICT_RENAME).contains(value)) {
            throw new IllegalArgumentException("onConflict must be 'skip' or 'rename', got '" + onConflict + "'");
        }
        return ON_CONFLICT_RENAME.equals(value);
    }
}
