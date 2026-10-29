package io.github.timganev.switchboard.systemtests;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Runs against a deployed system, not during the Maven build.
class SystemHealthTest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(strings = {"GATEWAY", "GUARDIAN", "LEDGER", "TRACE", "LEAK_LAB"})
    void shouldReportHealthUp(String service) throws Exception {
        String baseUrl = System.getenv(service + "_BASE_URL");
        assumeTrue(baseUrl != null, service + "_BASE_URL is not set");

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
