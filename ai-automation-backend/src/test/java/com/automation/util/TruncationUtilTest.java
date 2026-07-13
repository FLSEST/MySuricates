package com.automation.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TruncationUtilTest {

    // --- smart(s, head, tail) ---

    @Test
    void smart_null_returnsEmpty() {
        assertEquals("", TruncationUtil.smart(null, 100, 400));
    }

    @Test
    void smart_shorterThanBudget_returnsUnchanged() {
        assertEquals("short output", TruncationUtil.smart("short output", 100, 400));
    }

    @Test
    void smart_exactBudget_returnsUnchangedWithoutMarker() {
        String s = "a".repeat(500);
        String result = TruncationUtil.smart(s, 100, 400);
        assertEquals(s, result);
        assertFalse(result.contains("skipped"));
    }

    @Test
    void smart_longerThanBudget_keepsHeadAndTail() {
        String s = "H".repeat(100) + "M".repeat(300) + "T".repeat(400);
        String result = TruncationUtil.smart(s, 100, 400);
        assertTrue(result.startsWith("H".repeat(100)));
        assertTrue(result.endsWith("T".repeat(400)));
        assertFalse(result.contains("M"));
    }

    @Test
    void smart_marker_countsSkippedAndTotal() {
        String s = "x".repeat(800);
        String result = TruncationUtil.smart(s, 100, 400);
        assertTrue(result.contains("[... skipped 300 of 800 characters ...]"));
    }

    @Test
    void smart_errorAtEnd_survivesTruncation() {
        String s = "noise ".repeat(500) + "HTTP 502 Bad Gateway";
        String result = TruncationUtil.smart(s, 100, 400);
        assertTrue(result.endsWith("HTTP 502 Bad Gateway"));
    }

    // --- window(s, offset, max) ---

    @Test
    void window_null_returnsEmpty() {
        assertEquals("", TruncationUtil.window(null, 0, 8000));
    }

    @Test
    void window_offsetZero_returnsPrefixWithPosition() {
        String s = "abcdefghij";
        String result = TruncationUtil.window(s, 0, 5);
        assertTrue(result.contains("[showing characters 0-5 of 10]"));
        assertTrue(result.contains("abcde"));
        assertFalse(result.contains("fghij"));
    }

    @Test
    void window_midOffset_returnsSliceAndContinuationHint() {
        String s = "0123456789ABCDEFGHIJ";
        String result = TruncationUtil.window(s, 5, 10);
        assertTrue(result.contains("[showing characters 5-15 of 20]"));
        assertTrue(result.contains("56789ABCDE"));
        assertTrue(result.contains("Continue with ?offset=15"));
    }

    @Test
    void window_lastSlice_hasNoContinuationHint() {
        String s = "0123456789";
        String result = TruncationUtil.window(s, 5, 100);
        assertTrue(result.contains("[showing characters 5-10 of 10]"));
        assertTrue(result.contains("56789"));
        assertFalse(result.contains("Continue"));
    }

    @Test
    void window_offsetBeyondLength_returnsExplanation() {
        String result = TruncationUtil.window("abc", 10, 100);
        assertTrue(result.contains("offset 10"));
        assertTrue(result.contains("length 3"));
    }

    @Test
    void window_negativeOffset_clampsToZero() {
        String result = TruncationUtil.window("abcde", -5, 3);
        assertTrue(result.contains("[showing characters 0-3 of 5]"));
        assertTrue(result.contains("abc"));
    }

    // --- tail(s, max) ---

    @Test
    void tail_null_returnsEmpty() {
        assertEquals("", TruncationUtil.tail(null, 100));
    }

    @Test
    void tail_shorterThanMax_returnsUnchanged() {
        assertEquals("short", TruncationUtil.tail("short", 100));
    }

    // --- tailInline(s, max) ---

    @Test
    void tailInline_shorterThanMax_returnsUnchanged() {
        assertEquals("short", TruncationUtil.tailInline("short", 100));
    }

    @Test
    void tailInline_longerThanMax_keepsEndSingleLine() {
        String s = "prefix ".repeat(50) + "exit code 7";
        String result = TruncationUtil.tailInline(s, 50);
        assertTrue(result.endsWith("exit code 7"));
        assertTrue(result.startsWith("…"));
        assertEquals(51, result.length());
        assertFalse(result.contains("\n"));
    }

    @Test
    void tail_longerThanMax_keepsEndWithMarker() {
        String s = "x".repeat(900) + "HTTP 500";
        String result = TruncationUtil.tail(s, 100);
        assertTrue(result.endsWith("HTTP 500"));
        assertTrue(result.contains("[... skipped 808 of 908 characters ...]"));
        assertEquals(100, result.length() - result.indexOf("]\n") - 2);
    }
}
