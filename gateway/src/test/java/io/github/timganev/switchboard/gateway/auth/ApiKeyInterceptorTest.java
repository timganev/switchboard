package io.github.timganev.switchboard.gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyInterceptorTest {

    @Test
    void shouldReadTheKeyFromEitherHeader() {
        assertThat(ApiKeyInterceptor.key(request("Authorization", "Bearer sb-abc"))).isEqualTo("sb-abc");
        assertThat(ApiKeyInterceptor.key(request("Authorization", "bearer  sb-abc "))).isEqualTo("sb-abc");
        assertThat(ApiKeyInterceptor.key(request("x-api-key", "sb-abc"))).isEqualTo("sb-abc");
        assertThat(ApiKeyInterceptor.key(request("Authorization", "Basic dXNlcjpwYXNz"))).isNull();
        assertThat(ApiKeyInterceptor.key(request("Authorization", "Bearer "))).isNull();
        assertThat(ApiKeyInterceptor.key(new MockHttpServletRequest())).isNull();
    }

    @Test
    void shouldHashTheSameKeyTheSameWay() {
        assertThat(ApiKeys.hash("sb-abc")).isEqualTo(ApiKeys.hash("sb-abc")).hasSize(64).isNotEqualTo(ApiKeys.hash("sb-abd"));
    }

    private static MockHttpServletRequest request(String header, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(header, value);
        return request;
    }
}
