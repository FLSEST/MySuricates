package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunProcessRegistrationTest {

    private static Process process() {
        Process process = mock(Process.class);
        when(process.descendants()).thenReturn(Stream.empty());
        return process;
    }

    @Test
    void cancelTerminatesEveryRegisteredProcessOnce() {
        RunProcessRegistration registration = new RunProcessRegistration();
        AtomicBoolean cancelled = new AtomicBoolean();
        Process shell = process();
        Process cli = process();
        registration.register(shell, cancelled);
        registration.register(cli, cancelled);

        registration.cancel();
        registration.cancel();

        verify(shell, times(1)).destroyForcibly();
        verify(cli, times(1)).destroyForcibly();
    }

    @Test
    void finishedProcessesAreNotTerminated() {
        RunProcessRegistration registration = new RunProcessRegistration();
        Process done = process();
        registration.register(done, new AtomicBoolean());

        registration.unregister(done);
        registration.cancel();

        verify(done, never()).destroyForcibly();
    }

    @Test
    void registeringAfterCancelTerminatesImmediately() {
        RunProcessRegistration registration = new RunProcessRegistration();
        Process late = process();

        registration.register(late, new AtomicBoolean(true));

        verify(late).destroyForcibly();
    }
}
