package io.github.timganev.switchboard.trace.live;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleTemplateTest {

    private static final CallView CALL = CallView.of(JsonMapper.builder().build().readTree("""
            {"callId": "c-1", "team": "payments", "route": "SURROGATE", "locality": "CLOUD", "instance": "cloud-1",
             "model": "big-model", "inputTokens": 120, "outputTokens": 30, "latencyMs": 850, "httpStatus": 200,
             "timestamp": "2026-10-30T16:44:43Z",
             "privacy": {"verdict": "SURROGATE", "reasons": [{"type": "EGN", "count": 1}, {"type": "IBAN", "count": 2}]}}
            """));

    private final SpringTemplateEngine engine = engine();

    @Test
    void shouldReadARowFromATrace() {
        assertThat(CALL).isEqualTo(new CallView("c-1", "16:44:43", "payments", "surrogate", "EGN, IBAN ×2",
                "cloud-1 (cloud)", "big-model", 120, 30, 850, 200));
    }

    @Test
    void shouldPrependANewCallToTheTable() {
        String html = change(CALL, "added");

        assertThat(html).contains("hx-swap-oob=\"afterbegin:#calls\"", "id=\"call-c-1\"", "class=\"fresh\"",
                "class=\"route surrogate\"", "EGN, IBAN ×2", "cloud-1 (cloud)", "120 / 30");
        assertThat(html).doesNotContain("hx-swap-oob=\"true\"");
    }

    @Test
    void shouldReplaceTheRowOfACallThatGotItsDecisionLater() {
        String html = change(CALL, "updated");

        assertThat(html.strip()).startsWith("<tr");
        assertThat(html).contains("id=\"call-c-1\"", "hx-swap-oob=\"true\"").doesNotContain("afterbegin");
    }

    @Test
    void shouldRenderThePageWithTheLatestCallsAndTheLiveStream() {
        Context context = new Context();
        context.setVariable("calls", List.of(CALL));
        context.setVariable("team", "payments");

        String html = engine.process("console", context);

        assertThat(html).contains("id=\"call-c-1\"", "sse-connect=\"/live/stream?team=payments\"")
                .doesNotContain("hx-swap-oob", "th:text", "th:replace", "th:fragment");
    }

    private String change(CallView call, String change) {
        Context context = new Context();
        context.setVariable("call", call);
        context.setVariable("change", change);
        return engine.process("console", Set.of("change"), context);
    }

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
