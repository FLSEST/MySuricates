package com.automation.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceAddressTest {

    private static final String KEY_FILE = "'" + InstanceAddress.apiKeyFile() + "'";

    @Test
    void withoutInstallerSettings_theAddressIsLocalhostOnTheServerPort() {
        InstanceAddress address = new InstanceAddress("", "", 9123);

        assertThat(address.baseUrl()).isEqualTo("http://localhost:9123");
        assertThat(address.apiKeyCommand()).isEqualTo("cat " + KEY_FILE);
    }

    @Test
    void theInstallerUrlWins_evenInsideAContainerListeningOn9090() {
        InstanceAddress address = new InstanceAddress("http://localhost:9095/", "", 9090);

        assertThat(address.baseUrl()).isEqualTo("http://localhost:9095");
    }

    @Test
    void inAContainer_theKeyIsReadThroughDockerExec() {
        InstanceAddress address = new InstanceAddress("http://localhost:9091", "mysuricates-main", 9090);

        assertThat(address.apiKeyCommand()).isEqualTo("docker exec mysuricates-main cat " + KEY_FILE);
    }

    @Test
    void fillReplacesBothPlaceholdersAndLeavesOthersAlone() {
        InstanceAddress address = new InstanceAddress("http://localhost:9095", "", 9090);

        String filled = address.fill("MS={{BASE_URL}}/api/v1; K=$({{API_KEY_COMMAND}}); {{PROJECT_ID}} {{ open_todos }}");

        assertThat(filled).isEqualTo("MS=http://localhost:9095/api/v1; K=$(cat " + KEY_FILE + "); {{PROJECT_ID}} {{ open_todos }}");
        assertThat(address.fill(null)).isEmpty();
    }

    @Test
    void theKeyFileIsWhereApiKeyServiceWritesIt_inTheDataHome() {
        assertThat(InstanceAddress.apiKeyFile()).isEqualTo(DataHome.dir().resolve("api-key"));
    }
}
