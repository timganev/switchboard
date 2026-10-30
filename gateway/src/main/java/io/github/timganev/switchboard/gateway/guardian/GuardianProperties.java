package io.github.timganev.switchboard.gateway.guardian;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("switchboard.guardian")
public record GuardianProperties(URI url, Duration timeout) {
}
