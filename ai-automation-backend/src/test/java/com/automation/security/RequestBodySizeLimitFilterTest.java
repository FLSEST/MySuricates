package com.automation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The shared base of the per-endpoint body limits, tested through a minimal subclass so the
 * boundary, the error response and the chunked path are pinned independently of any one endpoint.
 */
class RequestBodySizeLimitFilterTest {

    private static final String PATH = "/api/v1/things";
    private static final long LIMIT = 10;

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter() {
        @Override protected String targetPath() { return PATH; }
        @Override protected long maxBytes() { return LIMIT; }
    };

    private static MockHttpServletRequest post(int bodyBytes) {
        var request = new MockHttpServletRequest("POST", PATH);
        request.setContent(new byte[bodyBytes]);
        return request;
    }

    /** Hides Content-Length, as with {@code Transfer-Encoding: chunked}. */
    private static HttpServletRequest chunked(int bodyBytes) {
        return new HttpServletRequestWrapper(post(bodyBytes)) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
    }

    /** A handler that reads the whole body in blocks and remembers how much it got. */
    private static FilterChain readingAll(AtomicInteger bytesRead) {
        return (req, res) -> bytesRead.set(req.getInputStream().readAllBytes().length);
    }

    /** A handler that reads the body byte by byte. */
    private static FilterChain readingSingleBytes(AtomicInteger bytesRead) {
        return (req, res) -> {
            InputStream in = req.getInputStream();
            int count = 0;
            while (in.read() != -1) count++;
            bytesRead.set(count);
        };
    }

    @Test
    void bodyExactlyAtTheLimitPassesThroughUnwrapped() throws Exception {
        var request = post((int) LIMIT);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void oneByteOverTheDeclaredLimitIsRejectedBeforeTheBodyIsRead() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(post((int) LIMIT + 1), response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/json");
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.get("error").asText()).isEqualTo("Payload too large");
        assertThat(body.get("maxBytes").asLong()).isEqualTo(10);
        // params mark the text as fully described, so the UI shows its own translation
        assertThat(body.get("params").get("maxBytes").asText()).isEqualTo("10");
        assertThat(body.get("timestamp").asText()).isNotBlank();
    }

    @Test
    void chunkedBodyWithinTheLimitIsReadCompletely() throws Exception {
        var bytesRead = new AtomicInteger(-1);
        var response = new MockHttpServletResponse();

        filter.doFilter(chunked((int) LIMIT), response, readingAll(bytesRead));

        assertThat(bytesRead).hasValue((int) LIMIT);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void chunkedBodyOverTheLimitIsCutOffWith413() throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(chunked((int) LIMIT + 1), response, readingAll(new AtomicInteger()));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"maxBytes\":10");
    }

    @Test
    void chunkedBodyReadByteByByteStopsAtTheLimit() throws Exception {
        var bytesRead = new AtomicInteger(-1);
        var withinLimit = new MockHttpServletResponse();
        filter.doFilter(chunked((int) LIMIT), withinLimit, readingSingleBytes(bytesRead));
        assertThat(bytesRead).hasValue((int) LIMIT);
        assertThat(withinLimit.getStatus()).isEqualTo(200);

        var overLimit = new MockHttpServletResponse();
        filter.doFilter(chunked((int) LIMIT + 1), overLimit, readingSingleBytes(new AtomicInteger()));
        assertThat(overLimit.getStatus()).isEqualTo(413);
    }

    @Test
    void chunkedBodyReadThroughTheReaderIsLimitedToo() throws Exception {
        var response = new MockHttpServletResponse();
        FilterChain readingText = (req, res) -> {
            var reader = req.getReader();
            while (reader.read() != -1) {
                // drain
            }
        };

        filter.doFilter(chunked(100), response, readingText);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    /** Once the handler has committed a response, the filter cannot replace it with a 413. */
    @Test
    void overflowAfterTheResponseIsCommittedKeepsTheCommittedResponse() throws Exception {
        var response = new MockHttpServletResponse();
        FilterChain streamingHandler = (req, res) -> {
            res.getWriter().write("partial");
            res.flushBuffer();
            req.getInputStream().readAllBytes();
        };

        filter.doFilter(chunked((int) LIMIT + 1), response, streamingHandler);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("partial");
    }

    @Test
    void otherMethodsAndPathsAreNotLimited() throws Exception {
        var put = new MockHttpServletRequest("PUT", PATH);
        put.setContent(new byte[100]);
        var otherPath = new MockHttpServletRequest("POST", PATH + "/nested");
        otherPath.setContent(new byte[100]);

        for (var request : new MockHttpServletRequest[] {put, otherPath}) {
            var chain = mock(FilterChain.class);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void methodMatchIsCaseInsensitive() throws Exception {
        var request = new MockHttpServletRequest("post", PATH);
        request.setContent(new byte[100]);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(413);
    }

    /** The target is matched on the decoded path — percent-encoding must not slip past the guard. */
    @Test
    void percentEncodedTargetPathIsStillLimited() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/%74hings");
        request.setContent(new byte[100]);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void targetPathIsMatchedWithinTheApplicationContext() throws Exception {
        var request = new MockHttpServletRequest("POST", "/app" + PATH);
        request.setContextPath("/app");
        request.setContent(new byte[100]);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void chunkedRequestsReachTheHandlerWrapped() throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        HttpServletRequest request = chunked(1);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));

        assertThat(seen.get()).isNotSameAs(request).isInstanceOf(HttpServletRequestWrapper.class);
    }
}
