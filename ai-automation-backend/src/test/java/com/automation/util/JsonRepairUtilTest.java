package com.automation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class JsonRepairUtilTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "not json at all", "123"})
    void repairReturnsNull_forInvalidInput(String input) {
        assertNull(JsonRepairUtil.repairTruncatedJson(input));
    }

    @Test
    void repairReturnsUnchanged_forValidJson() {
        String valid = "{\"name\":\"test\",\"items\":[1,2,3]}";
        assertEquals(valid, JsonRepairUtil.repairTruncatedJson(valid));
    }

    @Test
    void repairClosesTruncatedObject() {
        String truncated = "{\"a\":\"ok\",\"b\":[{\"c\":\"val\"";
        String repaired = JsonRepairUtil.repairTruncatedJson(truncated);
        assertNotNull(repaired);
        assertTrue(JsonRepairUtil.isValidJsonObject(repaired));
        assertTrue(repaired.contains("\"a\":\"ok\""));
        assertTrue(repaired.contains("\"c\":\"val\""));
    }

    @Test
    void repairRemovesTrailingCommaInArray() {
        String truncated = "{\"items\":[1,2,";
        String repaired = JsonRepairUtil.repairTruncatedJson(truncated);
        assertNotNull(repaired);
        assertTrue(JsonRepairUtil.isValidJsonObject(repaired));
        assertTrue(repaired.contains("[1,2]"));
    }

    @Test
    void repairClosesTruncatedString() {
        String truncated = "{\"text\":\"hello wor";
        String repaired = JsonRepairUtil.repairTruncatedJson(truncated);
        assertNotNull(repaired);
        assertTrue(JsonRepairUtil.isValidJsonObject(repaired));
        assertTrue(repaired.contains("hello wor"));
    }

    @Test
    void repairHandlesDeeplyNested() {
        String truncated = "{\"a\":{\"b\":{\"c\":[{\"d\":\"v\"";
        String repaired = JsonRepairUtil.repairTruncatedJson(truncated);
        assertNotNull(repaired);
        assertTrue(JsonRepairUtil.isValidJsonObject(repaired));
        assertTrue(repaired.contains("\"d\":\"v\""));
    }

    @Test
    void repairHandlesEscapedQuotes() {
        String truncated = "{\"t\":\"He said \\\"hel";
        String repaired = JsonRepairUtil.repairTruncatedJson(truncated);
        assertNotNull(repaired);
        assertTrue(JsonRepairUtil.isValidJsonObject(repaired));
    }

    @Test
    void repairHandlesTrailingCommaInObject() {
        String truncated = "{\"a\":1,\"b\":2,";
        String repaired = JsonRepairUtil.repairTruncatedJson(truncated);
        assertNotNull(repaired);
        assertTrue(JsonRepairUtil.isValidJsonObject(repaired));
    }

    @Test
    void isValidJsonObject_valid() {
        assertTrue(JsonRepairUtil.isValidJsonObject("{\"a\":1}"));
        assertTrue(JsonRepairUtil.isValidJsonObject("[1,2,3]"));
    }

    @Test
    void isValidJsonObject_invalid() {
        assertFalse(JsonRepairUtil.isValidJsonObject(null));
        assertFalse(JsonRepairUtil.isValidJsonObject(""));
        assertFalse(JsonRepairUtil.isValidJsonObject("{broken"));
    }

    @Test
    void repairHandlesEmptyObjectTruncation() {
        String truncated = "{\"workflows\":[{";
        String repaired = JsonRepairUtil.repairTruncatedJson(truncated);
        assertNotNull(repaired);
        assertTrue(JsonRepairUtil.isValidJsonObject(repaired));
    }

    @Test
    void repairHandlesMixedNesting() {
        // Simulates a real Claude CLI truncation: object with array of objects
        String truncated = "{\"needsClarification\":false,\"workflows\":[{\"name\":\"DORA Analyse\",\"blocks\":[{\"type\":\"simple\",\"config\":{\"prompt\":\"Analyze";
        String repaired = JsonRepairUtil.repairTruncatedJson(truncated);
        assertNotNull(repaired);
        assertTrue(JsonRepairUtil.isValidJsonObject(repaired));
    }
}
