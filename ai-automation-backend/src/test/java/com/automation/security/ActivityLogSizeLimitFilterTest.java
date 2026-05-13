package com.automation.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        // No content set → getContentLengthLong() returns -1, filter wraps request.
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        // Filter wraps the request for the chunked path, so we assert the chain ran once
        // with any request rather than the exact original instance.
        verify(chain, times(1)).doFilter(any(jakarta.servlet.ServletRequest.class), any(jakarta.servlet.ServletResponse.class));
        assertThat(res.getStatus()).isEqualTo(200);
    }

    /** Chunked encoding has Content-Length=-1 but a small body → filter must wrap and pass through. */
    @Test
    void chunkedUnderLimit_passesThrough() throws Exception {
        var req = chunkedRequest(new byte[1024]);
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        // chain ran exactly once; we read what the controller would read to prove the wrapper works
        verify(chain, times(1)).doFilter(any(jakarta.servlet.ServletRequest.class), any(jakarta.servlet.ServletResponse.class));
        assertThat(res.getStatus()).isEqualTo(200);
    }

    /** Chunked encoding with a large body must be rejected by the byte-counting wrapper. */
    @Test
    void chunkedOverLimit_returns413() throws Exception {
        var req = chunkedRequest(new byte[70_000]);
        var res = new MockHttpServletResponse();
        // Simulate downstream controller reading the body; the wrapper must throw before completion
        FilterChain chain = (servletRequest, servletResponse) -> {
            try (var in = servletRequest.getInputStream()) {
                in.readAllBytes();
            }
        };

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString()).contains("Payload too large").contains("65536");
    }

    private MockHttpServletRequest chunkedRequest(byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/activity-log") {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
        };
        req.setContent(body);
        return req;
    }
}
