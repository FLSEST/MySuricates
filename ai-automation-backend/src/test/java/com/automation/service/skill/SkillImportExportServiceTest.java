package com.automation.service.skill;

import com.automation.dto.request.ImportSkillRequest;
import com.automation.dto.response.ExportSkillResponse;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillImportExportServiceTest {

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillRegistry skillRegistry;

    @InjectMocks
    private SkillImportExportService importExportService;

    private Skill testSkill;

    @BeforeEach
    void setUp() {
        Capability cap = Capability.builder()
                .id("cap-1")
                .name("ocr")
                .description("Extract text from images")
                .handlerClass("com.automation.skills.handlers.OCRHandler")
                .inputSchema(Map.of("image", "string"))
                .outputSchema(Map.of("text", "string"))
                .async(false)
                .build();

        testSkill = Skill.builder()
                .id("skill-1")
                .name("Document Analyzer")
                .description("Analyzes documents")
                .icon("doc-icon")
                .category(SkillCategory.DOCUMENT)
                .aiContext("You are a document analyzer.")
                .configSchema(Map.of("lang", "string"))
                .version("1.0.0")
                .capabilities(new ArrayList<>(List.of(cap)))
                .build();
    }

    @Test
    void exportSkill_existingSkill_returnsExportResponse() {
        when(skillRepository.findById("skill-1")).thenReturn(Optional.of(testSkill));

        ExportSkillResponse response = importExportService.exportSkill("skill-1");

        assertThat(response.name()).isEqualTo("Document Analyzer");
        assertThat(response.description()).isEqualTo("Analyzes documents");
        assertThat(response.icon()).isEqualTo("doc-icon");
        assertThat(response.category()).isEqualTo(SkillCategory.DOCUMENT);
        assertThat(response.aiContext()).isEqualTo("You are a document analyzer.");
        assertThat(response.version()).isEqualTo("1.0.0");
        assertThat(response.capabilities()).hasSize(1);
        assertThat(response.capabilities().get(0).name()).isEqualTo("ocr");
    }

    @Test
    void exportSkill_nonExistent_throwsException() {
        when(skillRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> importExportService.exportSkill("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void importSkill_validRequest_createsAndRegistersSkill() {
        var capReq = new ImportSkillRequest.ImportCapabilityRequest(
                "ocr", "Extract text", "com.Handler",
                Map.of("image", "string"), Map.of("text", "string"), false
        );
        var request = new ImportSkillRequest(
                "Imported Skill", "An imported skill", "icon",
                SkillCategory.DOCUMENT, "AI context", Map.of(), "2.0.0",
                List.of(capReq)
        );

        when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
            Skill s = inv.getArgument(0);
            s.setId("skill-imported");
            return s;
        });

        Skill result = importExportService.importSkill(request);

        assertThat(result.getName()).isEqualTo("Imported Skill");
        assertThat(result.isBuiltin()).isFalse();
        assertThat(result.getCapabilities()).hasSize(1);
        assertThat(result.getVersion()).isEqualTo("2.0.0");
        verify(skillRegistry).register(result);
    }

    @Test
    void importSkill_nullCapabilities_createsSkillWithoutCapabilities() {
        var request = new ImportSkillRequest(
                "Simple Skill", "Simple", null,
                SkillCategory.CODE, null, null, null,
                null
        );

        when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
            Skill s = inv.getArgument(0);
            s.setId("skill-simple");
            return s;
        });

        Skill result = importExportService.importSkill(request);

        assertThat(result.getName()).isEqualTo("Simple Skill");
        assertThat(result.getCapabilities()).isEmpty();
        assertThat(result.getVersion()).isEqualTo("1.0.0"); // default
        assertThat(result.getConfigSchema()).isNotNull(); // default empty map
    }

    @Test
    void importSkill_nullSchemas_defaultsToEmptyMaps() {
        var capReq = new ImportSkillRequest.ImportCapabilityRequest(
                "test", "desc", "handler", null, null, true
        );
        var request = new ImportSkillRequest(
                "Skill", "desc", null, SkillCategory.CODE,
                null, null, null, List.of(capReq)
        );

        when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
            Skill s = inv.getArgument(0);
            s.setId("skill-id");
            return s;
        });

        Skill result = importExportService.importSkill(request);

        Capability cap = result.getCapabilities().get(0);
        assertThat(cap.getInputSchema()).isNotNull().isEmpty();
        assertThat(cap.getOutputSchema()).isNotNull().isEmpty();
        assertThat(cap.isAsync()).isTrue();
    }
}
