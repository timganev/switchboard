package io.github.timganev.switchboard.guardian.decision;

import io.github.timganev.switchboard.commons.Route;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LocalDecisionModelTest {

    private static final String TEXT = "The customer, Ivan Petrov, missed the follow-up visit after his knee surgery.";

    @Test
    void shouldKeepOnlyTheClaimsThatTheTextProves() {
        assertThat(LocalDecisionModel.categories("{\"names\": [\"Ivan Petrov\"], \"health\": [\"knee surgery\"]}", TEXT))
                .isEqualTo(new Classification(Set.of("NAME", "HEALTH"), false));
        // A role is not a name, and words that are not in the text prove nothing.
        assertThat(LocalDecisionModel.categories("{\"names\": [\"the customer\"], \"health\": [\"a broken arm\"]}", TEXT))
                .isEqualTo(Classification.NOTHING);
        assertThat(LocalDecisionModel.categories("{\"names\": [], \"health\": []}", TEXT)).isEqualTo(Classification.NOTHING);
    }

    @Test
    void shouldTreatAReplyWithoutAnAnswerAsAFailure() {
        assertThat(LocalDecisionModel.categories("I am not sure.", TEXT)).isEqualTo(Classification.FAILED);
        assertThat(LocalDecisionModel.categories("", TEXT)).isEqualTo(Classification.FAILED);
        assertThat(LocalDecisionModel.categories("{\"names\": [\"Ivan Petrov\"]}", TEXT)).isEqualTo(Classification.FAILED);
    }

    @Test
    void shouldAskTheLocalModelAndCountAnErrorAsAFinding() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://local");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://local/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("small"))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required[0]").value("names"))
                .andRespond(withSuccess("{\"choices\": [{\"message\": {\"content\": \"{\\\"names\\\": [\\\"Ivan Petrov\\\"], \\\"health\\\": []}\"}}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://local/v1/chat/completions")).andRespond(withServerError());
        LocalDecisionModel model = new LocalDecisionModel(builder.build(), "small", "none");

        assertThat(model.classify("Ivan Petrov is a nurse")).isEqualTo(new Classification(Set.of("NAME"), false));
        assertThat(model.classify("Ivan Petrov is a nurse")).isEqualTo(Classification.FAILED);
        assertThat(model.ref().name()).isEqualTo("small");
        server.verify();
    }

    @Test
    void shouldRefuseAnEscalationThatIsNotStrictEnough() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DecisionModelProperties(null, null, null, null, null, Route.SURROGATE));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new DecisionModelProperties(DecisionModelProperties.Type.LOCAL, URI.create("http://local"), null, null, null, null));
    }
}
