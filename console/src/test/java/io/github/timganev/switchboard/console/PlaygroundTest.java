package io.github.timganev.switchboard.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PlaygroundTest {

    private static final String INSPECTION = """
            {"route": "SURROGATE", "reasons": [{"type": "EGN", "count": 1}],
             "request": {"model": "m", "messages": [{"role": "USER", "parts": [{"type": "text", "text": "EGN 8807143345"}]}]},
             "substitutions": [{"type": "EGN", "value": "7501020018", "surrogate": "8807143345"}]}""";

    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> keysIssued = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String chatAnswer;
    private int chatStatus;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/inspect", exchange -> reply(exchange, 200, INSPECTION, null));
        server.createContext("/admin/keys", exchange -> {
            keysIssued.add(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 201, "{\"id\": \"k1\", \"key\": \"sb-issued\"}", null);
        });
        server.createContext("/v1/chat/completions", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, chatStatus, chatAnswer, "surrogate; reason=EGN");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void shouldShowWhatLeftAndWhatCameBack() {
        chatStatus = 200;
        chatAnswer = "{\"choices\": [{\"message\": {\"content\": \"About 7501020018\"}}]}";

        Playground.Result result = playground("an-admin-token").run("EGN 7501020018");

        assertThat(result.route()).isEqualTo("SURROGATE");
        assertThat(result.reasons()).containsExactly(new Playground.Reason("EGN", 1));
        assertThat(result.sent()).isEqualTo("EGN 8807143345");
        assertThat(result.swaps()).containsExactly(new Playground.Swap("EGN", "7501020018", "8807143345"));
        assertThat(result.answer()).isEqualTo("About 7501020018");
        assertThat(result.gatewayRoute()).isEqualTo("surrogate; reason=EGN");
        assertThat(result.error()).isNull();
    }

    @Test
    void shouldIssueOneKeyWithTheAdminTokenAndKeepUsingIt() {
        chatStatus = 200;
        chatAnswer = "{\"choices\": [{\"message\": {\"content\": \"ok\"}}]}";
        Playground playground = playground("an-admin-token");

        playground.run("one");
        playground.run("two");

        assertThat(keysIssued).containsExactly("Bearer an-admin-token");
        assertThat(authorizations).containsExactly("Bearer sb-issued", "Bearer sb-issued");
    }

    @Test
    void shouldPassOnTheGatewaysRefusal() {
        chatStatus = 403;
        chatAnswer = "{\"error\": {\"type\": \"permission_error\", \"message\": \"The request was blocked\"}}";

        Playground.Result result = playground(null).run("a private key");

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.error()).isEqualTo("The request was blocked");
        assertThat(result.answer()).isNull();
        assertThat(authorizations).containsExactly((String) null);
    }

    @Test
    void shouldRefuseAnEmptyPrompt() {
        assertThatIllegalArgumentException().isThrownBy(() -> playground(null).run(" "));
    }

    private Playground playground(String adminToken) {
        URI url = URI.create("http://localhost:" + server.getAddress().getPort());
        return new Playground(new ConsoleProperties(url, url, null, adminToken, null, null, Duration.ofSeconds(5)),
                JsonMapper.builder().build());
    }

    private static void reply(HttpExchange exchange, int status, String body, String route) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (route != null) {
            exchange.getResponseHeaders().set("X-Switchboard-Route", route);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
