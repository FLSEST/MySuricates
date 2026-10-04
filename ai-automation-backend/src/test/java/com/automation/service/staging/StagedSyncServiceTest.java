package com.automation.service.staging;

import com.automation.config.StagingConfig;
import com.automation.dto.response.SyncTargetResponse;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** URL, key-file and status handling of the staged sync (TODO 84a78bb7); the sync itself: StagedSyncIntegrationTest. */
class StagedSyncServiceTest {

    @Test
    void apiBaseAcceptsHostOnlyTrailingSlashAndApiPath() {
        assertThat(StagedSyncService.apiBase("http://test:9090")).isEqualTo("http://test:9090/api/v1");
        assertThat(StagedSyncService.apiBase(" http://test:9090/ ")).isEqualTo("http://test:9090/api/v1");
        assertThat(StagedSyncService.apiBase("http://test:9090/api/v1/")).isEqualTo("http://test:9090/api/v1");
    }

    @Test
    void keyIsTrimmedAndMissingOrEmptyMeansNone(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("prod.key");
        Files.writeString(key, "  secret-key\n");
        assertThat(StagedSyncService.readKey(key.toString())).isEqualTo("secret-key");

        Files.writeString(key, "\n");
        assertThat(StagedSyncService.readKey(key.toString())).isNull();
        assertThat(StagedSyncService.readKey(dir.resolve("absent.key").toString())).isNull();
        assertThat(StagedSyncService.readKey("")).isNull();
        assertThat(StagedSyncService.readKey(null)).isNull();
    }

    /**
     * The settings card asks for the targets every 30 s: two targets that hang must not hold it for the
     * normal 10 s + 30 s HTTP timeouts each — both are asked at once, with a short timeout.
     */
    @Test
    void hangingTargetsAreAskedInParallelWithAShortTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            try {
                Thread.sleep(8_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = "{\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            StagingConfig config = new StagingConfig();
            config.getTargets().getTest().setUrl(base + "/test");
            config.getTargets().getProd().setUrl(base + "/prod");
            StagedSyncService service = new StagedSyncService(config, mock(InstanceSyncHelper.class),
                    mock(SyncPreviewService.class), new InstanceHttpClient(mock(ApiKeyService.class), new ObjectMapper()),
                    mock(SealedSyncScope.class));

            long start = System.nanoTime();
            List<SyncTargetResponse> targets = service.targets();
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(targets).extracting(SyncTargetResponse::name).containsExactly("test", "prod");
            assertThat(targets).allSatisfy(t -> assertThat(t.reachable()).isFalse());
            assertThat(millis).isLessThan(5_000);
        } finally {
            server.stop(0);
        }
    }
}
