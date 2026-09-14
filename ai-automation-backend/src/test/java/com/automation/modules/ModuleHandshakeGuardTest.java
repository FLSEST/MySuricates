package com.automation.modules;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleHandshakeGuardTest {

    @Test
    void refusesWith404WhileDisabledAndFollowsRuntimeSwitches() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of());
        ModuleHandshakeGuard guard = new ModuleHandshakeGuard(registry, ModuleCatalog.TERMINAL);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        boolean allowed = guard.beforeHandshake(new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(servletResponse), null, new HashMap<>());

        assertThat(allowed).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());

        registry.applyUiOverrides(Map.of(ModuleCatalog.TERMINAL, true));
        assertThat(guard.beforeHandshake(new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, new HashMap<>())).isTrue();
    }
}
