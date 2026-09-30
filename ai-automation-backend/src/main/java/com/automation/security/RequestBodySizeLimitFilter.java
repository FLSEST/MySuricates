package com.automation.security;

import com.automation.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Instant;

/**
 * Caps the request body of one POST endpoint so a single oversized payload cannot block the
 * HikariCP pool (pool-size=1) or starve threads. Subclasses name the path and the limit.
 *
 * <p>Two checks:
 * <ul>
 *   <li>Fast path: when {@code Content-Length} is present and over the limit,
 *       reject before reading the body.</li>
 *   <li>Slow path: when {@code Content-Length} is missing (e.g.
 *       {@code Transfer-Encoding: chunked}), wrap the input stream so it
 *       throws {@link PayloadTooLargeException} after maxBytes are read.</li>
 * </ul>
 */
@Slf4j
public abstract class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    /** Exact request URI the limit applies to (POST only). */
    protected abstract String targetPath();

    /** Largest accepted body in bytes. */
    protected abstract long maxBytes();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Decoded path so a percent-encoded target path (e.g. %6Cayouts) cannot slip past the guard.
        return !("POST".equalsIgnoreCase(request.getMethod())
                && targetPath().equals(PATH_HELPER.getPathWithinApplication(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long limit = maxBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > limit) {
            rejectTooLarge(response, contentLength, limit);
            return;
        }

        // Content-Length known and within limit → no need to count bytes.
        if (contentLength >= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        // Content-Length unknown (chunked Transfer-Encoding) → wrap to enforce.
        try {
            filterChain.doFilter(new ByteLimitedRequest(request, limit), response);
        } catch (PayloadTooLargeException e) {
            if (response.isCommitted()) {
                log.warn("{} chunked payload exceeded {} bytes after response committed", targetPath(), limit);
                return;
            }
            rejectTooLarge(response, -1, limit);
        }
    }

    private void rejectTooLarge(HttpServletResponse response, long observed, long limit) throws IOException {
        log.warn("{} payload rejected: {} bytes exceeds limit {} bytes",
                targetPath(), observed >= 0 ? observed : "chunked >", limit);
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + ErrorCode.PAYLOAD_TOO_LARGE.name()
                + "\",\"error\":\"" + ErrorCode.PAYLOAD_TOO_LARGE.getDefaultMessage()
                + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"params\":{\"maxBytes\":\"" + limit + "\"}"
                + ",\"maxBytes\":" + limit + "}");
    }

    /** Unchecked because the servlet API only allows IOException through doFilter. */
    static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(long limit) {
            super("Body exceeded byte limit " + limit);
        }
    }

    /** Wraps a request so reads from the body stop at {@code limit} bytes. */
    private static final class ByteLimitedRequest extends HttpServletRequestWrapper {
        private final long limit;

        ByteLimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new ByteLimitedInputStream(super.getInputStream(), limit);
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8"));
        }
    }

    private static final class ByteLimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        ByteLimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b == -1) {
                return -1;
            }
            if (++read > limit) {
                throw new PayloadTooLargeException(limit);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            if (n == -1) {
                return -1;
            }
            read += n;
            if (read > limit) {
                throw new PayloadTooLargeException(limit);
            }
            return n;
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener l) { delegate.setReadListener(l); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
