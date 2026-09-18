package com.automation.metrics;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Counts the bytes a response writes, and lets one last header be added just before the first
 * byte leaves.
 *
 * <p>The bytes are counted as they stream past, never buffered: the whole point of the
 * measurement is to notice a response that is far too large, and a wrapper that held such a
 * response in memory to measure it would make the problem it detects worse.
 *
 * <p>The {@code beforeCommit} hook exists because {@code Server-Timing} has to be set while the
 * response is still uncommitted, yet the timings are only interesting once the work is done.
 * Firing it on the first write catches everything up to serialisation — wait, database and
 * controller — which is the part that explains a slow request.
 */
public class MeasuredResponse extends HttpServletResponseWrapper {

    private final Runnable beforeCommit;

    private CountingOutputStream stream;
    private PrintWriter writer;
    private boolean hookFired;
    private long bytes;

    public MeasuredResponse(HttpServletResponse response, Runnable beforeCommit) {
        super(response);
        this.beforeCommit = beforeCommit;
    }

    /** Bytes written so far. */
    public long bytesWritten() {
        return writer != null || stream != null ? bytes : 0L;
    }

    /** Flushes a writer we created ourselves, so nothing is left behind in its buffer. */
    public void finish() {
        if (writer != null) {
            writer.flush();
        }
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (stream == null) {
            stream = new CountingOutputStream(super.getOutputStream());
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            Charset charset = resolveCharset();
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset), false);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        finish();
        super.flushBuffer();
    }

    private Charset resolveCharset() {
        String encoding = getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private void fireHookOnce() {
        if (hookFired) return;
        hookFired = true;
        if (!isCommitted()) {
            beforeCommit.run();
        }
    }

    /** Passes every byte through unchanged, counting as it goes. */
    private final class CountingOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;

        private CountingOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            fireHookOnce();
            delegate.write(b);
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            fireHookOnce();
            delegate.write(b, off, len);
            bytes += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }
}
