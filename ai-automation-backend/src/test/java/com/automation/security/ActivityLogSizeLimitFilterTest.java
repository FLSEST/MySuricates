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

class ActivityLogSizeLimitFilterTest {

    private ActivityLogSizeLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ActivityLogSizeLimitFilter();
        ReflectionTestUtils.setField(filter, "maxBytes", 65536L);
    }

    @Test
    void postUnderLimit_passesThrough() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/activity-log");
        req.setContent(new byte[1024]);
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void postOverLimit_returns413() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/activity-log");
        req.setContent(new byte[70_000]);
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString()).contains("Payload too large").contains("65536");
    }

    @Test
    void postAtLimit_passesThrough() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/activity-log");
        req.setContent(new byte[65_536]);
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void getRequest_isNotFiltered() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/activity-log");
        req.setContent(new byte[70_000]);
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void postOnDifferentPath_isNotFiltered() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/workflows");
        req.setContent(new byte[70_000]);
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void missingContentLength_passesThrough() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/activity-log");
        // No content set → getContentLengthLong() returns -1
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }
}
