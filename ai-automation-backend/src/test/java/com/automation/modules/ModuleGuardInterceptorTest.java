package com.automation.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleGuardInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @RequiresModule(ModuleCatalog.CHAINS)
    static class ChainsController {
        public void list() {}

        @RequiresModule(ModuleCatalog.WEBHOOKS)
        public void webhookSpecific() {}
    }

    static class CoreController {
        public void list() {}

        @RequiresModule(ModuleCatalog.INTEGRATIONS)
        public void exportTodo() {}
    }

    @Test
    void passesWhenHandlerHasNoAnnotation() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = guard(Map.of()).preHandle(new MockHttpServletRequest(), response, handler(new CoreController(), "list"));

        assertThat(proceed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void passesNonHandlerMethodHandlers() throws Exception {
        assertThat(guard(Map.of()).preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void rejectsDisabledClassLevelModuleWith404Json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = guard(Map.of()).preHandle(new MockHttpServletRequest(), response, handler(new ChainsController(), "list"));

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("MODULE_DISABLED");
        assertThat(body.get("error").asText()).contains("chains").contains("automation.modules.chains=true");
    }

    @Test
    void passesEnabledClassLevelModule() throws Exception {
        boolean proceed = guard(Map.of("chains", "true"))
                .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler(new ChainsController(), "list"));

        assertThat(proceed).isTrue();
    }

    @Test
    void methodAnnotationWinsOverClassAnnotation() throws Exception {
        ModuleGuardInterceptor chainsOnly = guard(Map.of("chains", "true"));

        boolean proceed = chainsOnly.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler(new ChainsController(), "webhookSpecific"));

        assertThat(proceed).isFalse();
    }

    @Test
    void methodAnnotationOnCoreControllerGuardsOnlyThatMethod() throws Exception {
        ModuleGuardInterceptor core = guard(Map.of());

        assertThat(core.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler(new CoreController(), "list"))).isTrue();
        assertThat(core.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler(new CoreController(), "exportTodo"))).isFalse();
    }

    private ModuleGuardInterceptor guard(Map<String, String> modules) {
        return new ModuleGuardInterceptor(ModuleRegistry.of(modules, Map.of()), objectMapper);
    }

    private static HandlerMethod handler(Object bean, String method) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(method));
    }
}
