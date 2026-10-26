package com.tiktok.adminservice.config;

import com.tiktok.common.http.PooledRequestFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ActuatorSourcesProperties.class)
public class ActuatorClientConfig {

    /** No base URL and no internal token: each call names its service, and actuator asks for none. */
    @Bean
    public RestClient actuatorRestClient(ActuatorSourcesProperties properties) {
        return RestClient.builder()
                .requestFactory(PooledRequestFactory.create(properties.timeout(), properties.timeout()))
                .build();
    }
}
