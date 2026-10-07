package com.automation.executor.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SarifLocationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static JsonNode result(String artifactLocation, Integer startLine) throws Exception {
        String region = startLine == null ? "" : ", \"region\": {\"startLine\": " + startLine + "}";
        return json("{\"locations\": [{\"physicalLocation\": {\"artifactLocation\": " + artifactLocation + region + "}}]}");
    }

    @Test
    void should_reportPlaceholder_when_resultHasNoLocation() throws Exception {
        assertThat(SarifLocation.of(json("{}"), MissingNode.getInstance())).isEqualTo("(ohne Ort)");
        assertThat(SarifLocation.of(result("{\"uri\": \"  \"}", 3), MissingNode.getInstance())).isEqualTo("(ohne Ort)");
    }

    @Test
    void should_appendLine_onlyWhenPositive() throws Exception {
        assertThat(SarifLocation.of(result("{\"uri\": \"src/A.java\"}", 12), MissingNode.getInstance()))
                .isEqualTo("src/A.java:12");
        assertThat(SarifLocation.of(result("{\"uri\": \"src/A.java\"}", 0), MissingNode.getInstance()))
                .isEqualTo("src/A.java");
        assertThat(SarifLocation.of(result("{\"uri\": \"src/A.java\"}", null), MissingNode.getInstance()))
                .isEqualTo("src/A.java");
    }

    @Test
    void should_cutOffTheWorkspaceMount_fromFileUris() throws Exception {
        assertThat(SarifLocation.of(result("{\"uri\": \"file:///workspace/src/A.java\"}", 5), MissingNode.getInstance()))
                .isEqualTo("src/A.java:5");
        assertThat(SarifLocation.of(result("{\"uri\": \"/workspace/pom.xml\"}", null), MissingNode.getInstance()))
                .isEqualTo("pom.xml");
    }

    @Test
    void should_keepAbsolutePathsOutsideTheWorkspace() throws Exception {
        assertThat(SarifLocation.of(result("{\"uri\": \"file:///etc/passwd\"}", null), MissingNode.getInstance()))
                .isEqualTo("/etc/passwd");
    }

    @Test
    void should_readAFileUriWithSpaces_asAPath() throws Exception {
        assertThat(SarifLocation.of(result("{\"uri\": \"file:///workspace/my dir/a.txt\"}", 2), MissingNode.getInstance()))
                .isEqualTo("my dir/a.txt:2");
    }

    @Test
    void should_resolveARelativeUri_againstItsBase() throws Exception {
        JsonNode bases = json("{\"ROOTPATH\": {\"uri\": \"file:///workspace/sub/\"}}");

        assertThat(SarifLocation.of(result("{\"uri\": \"creds.txt\", \"uriBaseId\": \"ROOTPATH\"}", 1), bases))
                .isEqualTo("sub/creds.txt:1");
    }

    @Test
    void should_insertASlash_when_baseUriLacksOne() throws Exception {
        JsonNode bases = json("{\"SRC\": {\"uri\": \"file:///workspace/sub\"}}");

        assertThat(SarifLocation.of(result("{\"uri\": \"a.txt\", \"uriBaseId\": \"SRC\"}", null), bases))
                .isEqualTo("sub/a.txt");
    }

    @Test
    void should_followChainedBases() throws Exception {
        JsonNode bases = json("{"
                + "\"ROOT\": {\"uri\": \"file:///workspace/\"},"
                + "\"MOD\": {\"uri\": \"module/\", \"uriBaseId\": \"ROOT\"}}");

        assertThat(SarifLocation.of(result("{\"uri\": \"pom.xml\", \"uriBaseId\": \"MOD\"}", 7), bases))
                .isEqualTo("module/pom.xml:7");
    }

    @Test
    void should_leaveUriAsItIs_when_baseIsUnknownOrHasNoUri() throws Exception {
        JsonNode bases = json("{\"EMPTY\": {}}");

        assertThat(SarifLocation.of(result("{\"uri\": \"a.txt\", \"uriBaseId\": \"UNKNOWN\"}", null), bases))
                .isEqualTo("a.txt");
        assertThat(SarifLocation.of(result("{\"uri\": \"a.txt\", \"uriBaseId\": \"EMPTY\"}", null), bases))
                .isEqualTo("a.txt");
    }

    @Test
    void should_ignoreTheBase_forAbsoluteUris() throws Exception {
        JsonNode bases = json("{\"ROOT\": {\"uri\": \"file:///elsewhere/\"}}");

        assertThat(SarifLocation.of(result("{\"uri\": \"file:///workspace/x\", \"uriBaseId\": \"ROOT\"}", null), bases))
                .isEqualTo("x");
        assertThat(SarifLocation.of(result("{\"uri\": \"/workspace/y\", \"uriBaseId\": \"ROOT\"}", null), bases))
                .isEqualTo("y");
    }

    @Test
    void should_stopFollowingBases_afterTheDepthLimit() throws Exception {
        // a cyclic chain must terminate; after four hops the remaining base is no longer prefixed
        JsonNode bases = json("{\"A\": {\"uri\": \"a/\", \"uriBaseId\": \"A\"}}");

        assertThat(SarifLocation.of(result("{\"uri\": \"f.txt\", \"uriBaseId\": \"A\"}", null), bases))
                .isEqualTo("a/a/a/a/f.txt");
    }

    @Test
    void should_useOnlyTheFirstLocation() throws Exception {
        JsonNode result = json("{\"locations\": ["
                + "{\"physicalLocation\": {\"artifactLocation\": {\"uri\": \"first.txt\"}}},"
                + "{\"physicalLocation\": {\"artifactLocation\": {\"uri\": \"second.txt\"}}}]}");

        assertThat(SarifLocation.of(result, MissingNode.getInstance())).isEqualTo("first.txt");
    }
}
