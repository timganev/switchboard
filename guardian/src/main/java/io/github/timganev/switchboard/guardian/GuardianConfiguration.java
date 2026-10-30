package io.github.timganev.switchboard.guardian;

import io.github.timganev.switchboard.guardian.surrogate.SurrogateFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GuardianProperties.class)
class GuardianConfiguration {

    @Bean
    SurrogateFactory surrogateFactory() {
        return new SurrogateFactory();
    }
}
