package io.github.timganev.switchboard.gateway.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * With API keys on, the gateway connects to Postgres and checks a key on every call to the model APIs.
 */
@Configuration
@ConditionalOnProperty(name = "switchboard.auth.type", havingValue = "api-key")
@EnableConfigurationProperties(AuthProperties.class)
class ApiKeyConfiguration implements WebMvcConfigurer {

    // Looked up on first use, since this class also defines the bean.
    private final ObjectProvider<ApiKeys> apiKeys;

    ApiKeyConfiguration(ObjectProvider<ApiKeys> apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Bean
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    ApiKeys apiKeys(JdbcClient jdbc, Clock clock) {
        return new ApiKeys(jdbc, clock);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiKeyInterceptor(apiKeys.getObject())).addPathPatterns("/v1/**");
    }
}
