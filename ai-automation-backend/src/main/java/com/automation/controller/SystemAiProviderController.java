package com.automation.controller;

import com.automation.dto.response.SystemCapabilitiesResponse;
import com.automation.executor.BlockTypeModules;
import com.automation.modules.ModuleRegistry;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.ai.PiCatalogService;
import com.automation.service.ai.catalog.ModelCatalog;
import com.automation.service.ai.catalog.ModelCatalogService;
import com.automation.service.assistant.context.BlockTypesIndexSection;
import com.automation.service.system.SystemIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** AI-Provider- und Capability-Auskunft unter /api/v1/system. */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemAiProviderController {

    private final AiProviderRegistry aiProviderRegistry;
    private final PiCatalogService piCatalogService;
    private final ModelCatalogService modelCatalogService;
    private final SystemIdentityService systemIdentity;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final ModuleRegistry moduleRegistry;

    @Value("${automation.environment:Dev}")
    private String environment;

    @GetMapping("/ai-providers")
    public Map<String, Object> aiProviders() {
        return Map.of(
                "providers", aiProviderRegistry.getAvailableProviderIds(),
                "default", aiProviderRegistry.getDefaultProviderId(),
                "capabilities", aiProviderRegistry.getCapabilitiesByProvider()
        );
    }

    @GetMapping("/capabilities")
    public SystemCapabilitiesResponse capabilities() {
        List<SystemCapabilitiesResponse.RouteCapabilityResponse> routes = requestMappingHandlerMapping
                .getHandlerMethods()
                .entrySet()
                .stream()
                .flatMap(entry -> routeCapabilities(entry.getKey(), entry.getValue()).stream())
                .sorted(Comparator.comparing(SystemCapabilitiesResponse.RouteCapabilityResponse::path)
                        .thenComparing(r -> String.join(",", r.methods())))
                .toList();

        List<SystemCapabilitiesResponse.BlockTypeCapabilityResponse> blockTypes = BlockTypesIndexSection.BLOCK_TYPES
                .stream()
                // A type whose module is off is not offered: the editor must not put a block into a
                // workflow this instance refuses to run. Saving such a definition stays possible.
                .filter(e -> BlockTypeModules.requiredFor(e.type()).map(moduleRegistry::isEnabled).orElse(true))
                .map(e -> new SystemCapabilitiesResponse.BlockTypeCapabilityResponse(e.type(), e.summary()))
                .toList();

        return new SystemCapabilitiesResponse(
                "v1",
                systemIdentity.getVersion(),
                systemIdentity.getBuildType(),
                environment,
                routes,
                blockTypes,
                moduleRegistry.preset().key(),
                moduleRegistry.describe().stream().map(SystemCapabilitiesResponse.ModuleCapabilityResponse::from).toList(),
                Instant.now(),
                moduleRegistry.uiEditable()
        );
    }

    private List<SystemCapabilitiesResponse.RouteCapabilityResponse> routeCapabilities(
            RequestMappingInfo info,
            HandlerMethod handlerMethod) {
        List<String> paths = routePaths(info);
        if (paths.isEmpty()) {
            return List.of();
        }

        List<String> methods = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name)
                .sorted()
                .toList();
        if (methods.isEmpty()) {
            methods = List.of("ANY");
        }

        List<String> finalMethods = methods;
        return paths.stream()
                .filter(path -> path.startsWith("/api/"))
                .map(path -> new SystemCapabilitiesResponse.RouteCapabilityResponse(
                        path,
                        finalMethods,
                        handlerMethod.getBeanType().getSimpleName(),
                        handlerMethod.getMethod().getName()))
                .toList();
    }

    private List<String> routePaths(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues().stream().sorted().toList();
        }
        if (info.getPatternsCondition() != null) {
            return info.getPatternsCondition().getPatterns().stream().sorted().toList();
        }
        return List.of();
    }

    /**
     * Returns the pi runtime catalog read from {@code ~/.pi/agent}: which backend
     * providers are authenticated, which are custom-defined in models.json, and
     * what pi's own default-provider/-model is set to.
     */
    @GetMapping("/ai-providers/PI/catalog")
    public PiCatalogService.PiCatalog piCatalog() {
        return piCatalogService.getCatalog();
    }

    /**
     * Models the provider offers on this host, discovered from the CLI / its local caches and
     * served from a TTL cache. Never fails because of a missing CLI — see {@link ModelCatalog#available()}.
     */
    @GetMapping("/ai-providers/{id}/models")
    public ModelCatalog models(@PathVariable String id) {
        return modelCatalogService.getCatalog(id, false);
    }

    /** Bypasses the cache and probes the host now. */
    @PostMapping("/ai-providers/{id}/models/refresh")
    public ModelCatalog refreshModels(@PathVariable String id) {
        return modelCatalogService.getCatalog(id, true);
    }
}
