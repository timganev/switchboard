package io.github.timganev.switchboard.console;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class ConsoleConfiguration {

    @Bean
    Playground playground(ConsoleProperties properties, JsonMapper jsonMapper) {
        return new Playground(properties, jsonMapper);
    }
}
