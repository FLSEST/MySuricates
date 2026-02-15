package com.automation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    private ApiKeyAuthFilter filter;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter();
        SecurityContextHolder.clearContext();
    }

    @Test
    void securityDisabled_authenticatesAsAnonymous() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", false);
        ReflectionTestUtils.setField(filter, "configuredApiKey", "");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("anonymous");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_blankApiKey_authenticatesAsAnonymous() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        ReflectionTestUtils.setField(filter, "configuredApiKey", "");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("anonymous");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_validApiKey_authenticates() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        ReflectionTestUtils.setField(filter, "configuredApiKey", "my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn("my-secret-key");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("api-key-user");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_invalidApiKey_doesNotAuthenticate() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        ReflectionTestUtils.setField(filter, "configuredApiKey", "my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn("wrong-key");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_missingApiKey_doesNotAuthenticate() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        ReflectionTestUtils.setField(filter, "configuredApiKey", "my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
