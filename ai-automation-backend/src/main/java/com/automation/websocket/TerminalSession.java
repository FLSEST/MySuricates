package com.automation.websocket;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
public class TerminalSession {

    private final WebSocketSession wsSession;
    private final DockerClient dockerClient;
    private final String containerId;
    private final String execId;
    private final PipedOutputStream stdinWriter;
    private volatile boolean destroyed = false;

    public TerminalSession(WebSocketSession wsSession, DockerClient dockerClient, String containerId) {
        this.wsSession = wsSession;
        this.dockerClient = dockerClient;
        this.containerId = containerId;

        // Create exec instance with TTY — run bash shell for interactive terminal access
        var execCreate = dockerClient.execCreateCmd(containerId)
                .withTty(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("bash", "-l")
                .exec();
        this.execId = execCreate.getId();

        // Set up piped streams for stdin
        PipedInputStream pipedIn = new PipedInputStream();
        PipedOutputStream pipedOut;
        try {
            pipedOut = new PipedOutputStream(pipedIn);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create piped streams", e);
        }
        this.stdinWriter = pipedOut;

        // Start exec with TTY and stream output to WebSocket
        dockerClient.execStartCmd(execId)
                .withTty(true)
                .withStdIn(pipedIn)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (destroyed) return;
                        try {
                            String data = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            synchronized (wsSession) {
                                if (wsSession.isOpen()) {
                                    wsSession.sendMessage(new TextMessage(data));
                                }
                            }
                        } catch (IOException e) {
                            log.warn("Failed to send terminal output to WebSocket: {}", e.getMessage());
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        if (!destroyed) {
                            log.error("Terminal exec error for container {}: {}", containerId, throwable.getMessage());
                        }
                    }

                    @Override
                    public void onComplete() {
                        log.info("Terminal exec completed for container {}", containerId);
                        destroy();
                    }
                });

        log.info("Terminal session started for container {} (exec {})", containerId, execId);
    }

    public void write(String data) {
        if (destroyed) return;
        try {
            stdinWriter.write(data.getBytes(StandardCharsets.UTF_8));
            stdinWriter.flush();
        } catch (IOException e) {
            log.warn("Failed to write to terminal stdin: {}", e.getMessage());
        }
    }

    public void resize(int cols, int rows) {
        if (destroyed) return;
        try {
            dockerClient.resizeExecCmd(execId)
                    .withSize(rows, cols)
                    .exec();
        } catch (Exception e) {
            log.warn("Failed to resize terminal: {}", e.getMessage());
        }
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        try {
            stdinWriter.close();
        } catch (IOException e) {
            log.debug("Error closing stdin writer: {}", e.getMessage());
        }
        log.info("Terminal session destroyed for container {}", containerId);
    }
}
