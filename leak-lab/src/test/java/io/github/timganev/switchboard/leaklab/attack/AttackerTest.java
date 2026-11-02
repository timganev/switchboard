package io.github.timganev.switchboard.leaklab.attack;

import io.github.timganev.switchboard.leaklab.dataset.Person;
import io.github.timganev.switchboard.leaklab.dataset.Population;
import io.github.timganev.switchboard.leaklab.model.ModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AttackerTest {

    private final List<Person> candidates = Population.generate(3, 42);

    @Test
    void shouldTakeTheLastCandidateTheModelNames() {
        assertThat(Attacker.chosen("P01 is from Varna, but the job fits P02.\nP02", candidates)).contains("P02");
        assertThat(Attacker.chosen("Maybe P07, which is not on the list.", candidates)).isEmpty();
        assertThat(Attacker.chosen("NONE", candidates)).isEmpty();
    }

    @Test
    void shouldShowTheAttackerOnlyTheMessageAndTheCandidates() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://attacker");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://attacker/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("attacker-model"))
                .andExpect(jsonPath("$.temperature").value(0.0))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("EGN ***")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(candidates.get(1).name())))
                .andRespond(withSuccess("{\"choices\": [{\"message\": {\"role\": \"assistant\", \"content\": \"P02\"}}]}", MediaType.APPLICATION_JSON));

        assertThat(new Attacker(new ModelClient(builder.build(), "attacker-model", null)).identify("A nurse from Varna, EGN ***", candidates)).contains("P02");
        server.verify();
    }
}
