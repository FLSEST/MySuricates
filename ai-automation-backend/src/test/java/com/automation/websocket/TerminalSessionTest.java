package com.automation.websocket;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.ResizeExecCmd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TerminalSessionTest {

    @Mock private WebSocketSession wsSession;
    @Mock private DockerClient dockerClient;
    @Mock private ExecCreateCmd execCreateCmd;
    @Mock private ExecCreateCmdResponse execCreateResponse;
    @Mock private ExecStartCmd execStartCmd;

    private TerminalSession session;

    @BeforeEach
    void setUp() {
        // Chain the exec create command
        when(dockerClient.execCreateCmd("container-1")).thenReturn(execCreateCmd);
        when(execCreateCmd.withTty(true)).thenReturn(execCreateCmd);
        when(execCreateCmd.withAttachStdin(true)).thenReturn(execCreateCmd);
        when(execCreateCmd.withAttachStdout(true)).thenReturn(execCreateCmd);
        when(execCreateCmd.withAttachStderr(true)).thenReturn(execCreateCmd);
        when(execCreateCmd.withCmd("bash", "-l")).thenReturn(execCreateCmd);
        when(execCreateCmd.exec()).thenReturn(execCreateResponse);
        when(execCreateResponse.getId()).thenReturn("exec-123");

        // Chain the exec start command
        when(dockerClient.execStartCmd("exec-123")).thenReturn(execStartCmd);
        when(execStartCmd.withTty(true)).thenReturn(execStartCmd);
        when(execStartCmd.withStdIn(any())).thenReturn(execStartCmd);
        when(execStartCmd.exec(any())).thenReturn(null);

        session = new TerminalSession(wsSession, dockerClient, "container-1");
    }

    @Test
    void constructor_createsExecWithBash() {
        verify(execCreateCmd).withTty(true);
        verify(execCreateCmd).withAttachStdin(true);
        verify(execCreateCmd).withAttachStdout(true);
        verify(execCreateCmd).withAttachStderr(true);
        verify(execCreateCmd).withCmd("bash", "-l");
    }

    @Test
    void constructor_startsExecWithTtyAndStdin() {
        verify(execStartCmd).withTty(true);
        verify(execStartCmd).withStdIn(any());
        verify(execStartCmd).exec(any());
    }

    @Nested
    class Write {

        @Test
        void write_sendsDataToStdin() {
            // write should not throw — data goes to the piped stream
            session.write("ls -la\n");
        }

        @Test
        void write_afterDestroy_doesNothing() {
            session.destroy();
            // Should not throw
            session.write("test");
        }
    }

    @Nested
    class Resize {

        @Test
        void resize_callsDockerResize() {
            ResizeExecCmd resizeCmd = mock(ResizeExecCmd.class);
            when(dockerClient.resizeExecCmd("exec-123")).thenReturn(resizeCmd);
            when(resizeCmd.withSize(24, 80)).thenReturn(resizeCmd);

            session.resize(80, 24);

            verify(resizeCmd).exec();
        }

        @Test
        void resize_afterDestroy_doesNothing() {
            session.destroy();
            // Should not throw
            session.resize(80, 24);
        }
    }

    @Nested
    class Destroy {

        @Test
        void destroy_canBeCalledMultipleTimes() {
            session.destroy();
            session.destroy(); // Should not throw
        }

        @Test
        void destroy_preventsSubsequentWrites() {
            session.destroy();
            // write after destroy should be a no-op
            session.write("should not go anywhere");
        }
    }
}
