package com.automation.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DashboardLayoutSizeLimitFilterTest {

    private DashboardLayoutSizeLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DashboardLayoutSizeLimitFilter();
        ReflectionTestUtils.setField(filter, "maxBytes", 262144L);
    }

    @Test
    void postUnderLimit_passesThrough() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/dashboard/layouts");
        req.setContent(new byte[10_000]);
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void postOverLimit_returns413() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/dashboard/layouts");
        req.setContent(new byte[300_000]);
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString()).contains("Payload too large").contains("262144");
    }

    @Test
    void otherMethodsAndPaths_areNotLimited() throws Exception {
        var patch = new MockHttpServletRequest("PATCH", "/api/v1/dashboard/layouts/version-1");
        patch.setContent(new byte[300_000]);
        var otherPath = new MockHttpServletRequest("POST", "/api/v1/activity-log");
        otherPath.setContent(new byte[300_000]);
        var chain = mock(FilterChain.class);

        filter.doFilter(patch, new MockHttpServletResponse(), chain);
        filter.doFilter(otherPath, new MockHttpServletResponse(), chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
