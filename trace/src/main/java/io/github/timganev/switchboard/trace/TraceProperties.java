package io.github.timganev.switchboard.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("switchboard.trace")
public record TraceProperties(OpenSearch opensearch) {

    public record OpenSearch(URI url, Duration timeout) {
    }
}
