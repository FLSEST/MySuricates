package com.automation.concurrency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DynamicSemaphoreTest {

    @Test
    void setMaxPermits_increase_addsPermits() {
        var sem = new DynamicSemaphore(3);
        assertEquals(3, sem.availablePermits());

        sem.setMaxPermits(5);

        assertEquals(5, sem.getMaxPermits());
        assertEquals(5, sem.availablePermits());
    }

    @Test
    void setMaxPermits_decrease_reducesPermits() {
        var sem = new DynamicSemaphore(5);
        assertEquals(5, sem.availablePermits());

        sem.setMaxPermits(2);

        assertEquals(2, sem.getMaxPermits());
        assertEquals(2, sem.availablePermits());
    }

    @Test
    void setMaxPermits_decrease_belowAcquired_goesNegative() throws InterruptedException {
        var sem = new DynamicSemaphore(3);
        sem.acquire(3); // All 3 acquired
        assertEquals(0, sem.availablePermits());

        sem.setMaxPermits(1); // Reduce to 1 while all 3 are held

        assertEquals(1, sem.getMaxPermits());
        // Available should be negative: 0 - 2 = -2
        assertEquals(-2, sem.availablePermits());

        // New acquire should not succeed immediately
        assertFalse(sem.tryAcquire());
    }

    @Test
    void setMaxPermits_decrease_thenRelease_drainsGradually() throws InterruptedException {
        var sem = new DynamicSemaphore(3);
        sem.acquire(3);

        sem.setMaxPermits(1); // available = -2

        sem.release(); // available = -1
        assertFalse(sem.tryAcquire());

        sem.release(); // available = 0
        assertFalse(sem.tryAcquire());

        sem.release(); // available = 1
        assertTrue(sem.tryAcquire()); // Now it works
    }

    @Test
    void setMaxPermits_sameValue_noChange() {
        var sem = new DynamicSemaphore(5);
        sem.setMaxPermits(5);
        assertEquals(5, sem.getMaxPermits());
        assertEquals(5, sem.availablePermits());
    }

    @Test
    void setMaxPermits_rejectsZero() {
        var sem = new DynamicSemaphore(3);
        assertThrows(IllegalArgumentException.class, () -> sem.setMaxPermits(0));
    }

    @Test
    void setMaxPermits_rejectsNegative() {
        var sem = new DynamicSemaphore(3);
        assertThrows(IllegalArgumentException.class, () -> sem.setMaxPermits(-1));
    }

    @Test
    void getMaxPermits_reflectsLatestValue() {
        var sem = new DynamicSemaphore(10);
        assertEquals(10, sem.getMaxPermits());

        sem.setMaxPermits(3);
        assertEquals(3, sem.getMaxPermits());

        sem.setMaxPermits(20);
        assertEquals(20, sem.getMaxPermits());
    }

    @Test
    void acquireRelease_worksNormally() throws InterruptedException {
        var sem = new DynamicSemaphore(2);
        sem.acquire();
        assertEquals(1, sem.availablePermits());
        sem.release();
        assertEquals(2, sem.availablePermits());
    }
}
