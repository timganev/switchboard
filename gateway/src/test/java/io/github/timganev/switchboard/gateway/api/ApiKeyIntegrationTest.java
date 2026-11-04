package io.github.timganev.switchboard.gateway.api;

import io.github.timganev.switchboard.commons.Route;
import io.github.timganev.switchboard.commons.chat.ChatResponse;
import io.github.timganev.switchboard.commons.chat.StopReason;
import io.github.timganev.switchboard.commons.chat.TextPart;
import io.github.timganev.switchboard.commons.chat.Usage;
import io.github.timganev.switchboard.commons.privacy.Inspection;
import io.github.timganev.switchboard.gateway.events.CallRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"switchboard.auth.type=api-key", "switchboard.auth.admin-token=" + ApiKeyIntegrationTest.ADMIN_TOKEN})
@AutoConfigureMockMvc
@Testcontainers
class ApiKeyIntegrationTest {

    static final String ADMIN_TOKEN = "an-admin-token-of-at-least-32-characters";
    private static final ChatResponse ANSWER = new ChatResponse("resp-1", "qwen36", List.of(new TextPart("Hello")), StopReason.END_TURN,
            new Usage(12, 2));
    private static final String OPENAI_BODY = """
            {"model": "qwen36", "messages": [{"role": "user", "content": "Hi"}]}""";
    private static final String ANTHROPIC_BODY = """
            {"model": "qwen36", "max_tokens": 100, "messages": [{"role": "user", "content": "Hi"}]}""";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @MockitoBean
    private ChatPipeline pipeline;

    @BeforeEach
    void answer() {
        when(pipeline.send(any(), any())).thenReturn(new ChatPipeline.Answer(new Inspection(Route.CLOUD, List.of(), null, null), ANSWER, List.of()));
    }

    @Test
    void shouldTakeTheTeamFromTheKeyInBothFormats() throws Exception {
        String key = issue("payments").path("key").asString();

        answered(post("/v1/chat/completions").header("Authorization", "Bearer " + key).header("X-Switchboard-Team", "someone-else"), OPENAI_BODY);
        answered(post("/v1/messages").header("x-api-key", key), ANTHROPIC_BODY);

        ArgumentCaptor<CallRecord> calls = ArgumentCaptor.forClass(CallRecord.class);
        verify(pipeline, times(2)).send(calls.capture(), any());
        assertThat(calls.getAllValues()).extracting(CallRecord::team).containsOnly("payments");
    }

    @Test
    void shouldRefuseAMissingOrUnknownKeyInTheCallersFormat() throws Exception {
        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(OPENAI_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("authentication_error"));
        mockMvc.perform(post("/v1/messages").header("x-api-key", "sb-not-a-key").contentType(MediaType.APPLICATION_JSON).content(ANTHROPIC_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("authentication_error"));
    }

    @Test
    void shouldStopARevokedKeyOnTheNextRequest() throws Exception {
        JsonNode issued = issue("search");
        String key = issued.path("key").asString();
        answered(post("/v1/chat/completions").header("Authorization", "Bearer " + key), OPENAI_BODY);

        mockMvc.perform(delete("/admin/keys/" + issued.path("id").asString()).header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key).contentType(MediaType.APPLICATION_JSON).content(OPENAI_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldKeepOnlyAHashAndShowOnlyAPrefix() throws Exception {
        String key = issue("reports").path("key").asString();
        answered(post("/v1/chat/completions").header("Authorization", "Bearer " + key), OPENAI_BODY);

        assertThat(key).startsWith("sb-").hasSize(46);
        assertThat(jdbc.sql("SELECT count(*) FROM api_key WHERE key_hash = :key OR key_prefix = :key").param("key", key).query(Long.class).single())
                .isZero();
        mockMvc.perform(get("/admin/keys").param("team", "reports").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prefix").value(key.substring(0, 10)))
                .andExpect(jsonPath("$[0].key").doesNotExist())
                .andExpect(jsonPath("$[0].lastUsedAt").exists());
    }

    @Test
    void shouldIssueKeysOnlyForTheAdmin() throws Exception {
        mockMvc.perform(post("/admin/keys").contentType(MediaType.APPLICATION_JSON).content("{\"team\": \"payments\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/keys").header("Authorization", "Bearer " + ADMIN_TOKEN.replace('a', 'b'))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team\": \"payments\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/keys").header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team\": \" \"}"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode issue(String team) throws Exception {
        String body = mockMvc.perform(post("/admin/keys").header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"team\": \"" + team + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value(startsWith("sb-")))
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body);
    }

    private void answered(MockHttpServletRequestBuilder request, String body) throws Exception {
        MvcResult result = mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }
}
