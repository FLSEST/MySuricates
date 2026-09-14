package com.automation.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerminalStdinStreamTest {

    private final TerminalStdinStream stream = new TerminalStdinStream();

    @Test
    void read_returnsChunksInOrder_splitAcrossSmallBuffers() throws IOException {
        stream.write("hello".getBytes(StandardCharsets.UTF_8));
        stream.write(" world".getBytes(StandardCharsets.UTF_8));

        byte[] buffer = new byte[3];
        StringBuilder read = new StringBuilder();
        int n;
        stream.close();
        while ((n = stream.read(buffer, 0, buffer.length)) != -1) {
            read.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        }

        assertThat(read).hasToString("hello world");
    }

    @Test
    void read_singleByte_returnsUnsignedValue() throws IOException {
        stream.write(new byte[]{(byte) 0xC3});

        assertThat(stream.read()).isEqualTo(0xC3);
    }

    @Test
    void read_blocksUntilInputArrives() throws Exception {
        CompletableFuture<Integer> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return stream.read(new byte[8], 0, 8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(pending).isNotDone();
        stream.write(new byte[]{'x'});

        assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    void close_wakesBlockedReaderWithEndOfStream_andStaysAtEnd() throws Exception {
        CompletableFuture<Integer> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return stream.read(new byte[8], 0, 8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        stream.close();

        assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(-1);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void close_deliversInputWrittenBeforeClose() throws IOException {
        stream.write(new byte[]{'a', 'b'});
        stream.close();

        assertThat(stream.read(new byte[8], 0, 8)).isEqualTo(2);
        assertThat(stream.read()).isEqualTo(-1);
    }

    @Test
    void write_afterClose_isRejected() {
        stream.close();

        assertThatThrownBy(() -> stream.write(new byte[]{'x'}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void write_beyondPendingLimit_isRejected_andFreedByReading() throws IOException {
        stream.write(new byte[(int) TerminalStdinStream.MAX_PENDING_BYTES]);

        assertThatThrownBy(() -> stream.write(new byte[]{'x'}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("buffer full");

        stream.read(new byte[16], 0, 16);
        stream.write(new byte[]{'x'});
    }

    @Test
    void write_emptyInput_isIgnored() throws IOException {
        stream.write(new byte[0]);
        stream.write(new byte[]{'z'});

        assertThat(stream.read()).isEqualTo('z');
    }
}
