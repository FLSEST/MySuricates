package com.automation.controller;

import com.automation.dto.request.InstallChainTemplateRequest;
import com.automation.dto.response.ChainInstallResponse;
import com.automation.dto.response.ChainTemplateResponse;
import com.automation.entity.WorkflowChain;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.chain.BuiltInChainTemplate;
import com.automation.service.chain.ChainTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiresModule(ModuleCatalog.CHAINS)
@RestController
@RequestMapping("/api/v1/chain-templates")
@RequiredArgsConstructor
public class ChainTemplateController {

    private final ChainTemplateService chainTemplateService;

    @GetMapping
    public List<ChainTemplateResponse> listTemplates() {
        return chainTemplateService.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public ChainTemplateResponse getTemplate(@PathVariable String id) {
        return toResponse(chainTemplateService.findById(id));
    }

    @PostMapping("/{id}/install")
    public ResponseEntity<ChainInstallResponse> installTemplate(
            @PathVariable String id,
            @Valid @RequestBody InstallChainTemplateRequest request) {
        WorkflowChain chain = chainTemplateService.install(id, request.projectId(), request.chainName(), request.locale());
        List<String> workflowIds = chain.getEntries().stream()
                .map(e -> e.getWorkflowId())
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ChainInstallResponse(chain.getId(), chain.getName(), workflowIds));
    }

    private ChainTemplateResponse toResponse(BuiltInChainTemplate t) {
        return new ChainTemplateResponse(
                t.id(), t.name(), t.description(), t.icon(),
                t.entries().stream()
                        .map(e -> new ChainTemplateResponse.EntryInfo(e.templateId(), e.label()))
                        .toList()
        );
    }
}
