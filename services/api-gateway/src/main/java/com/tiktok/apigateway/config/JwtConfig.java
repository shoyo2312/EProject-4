package com.tiktok.apigateway.config;

import com.tiktok.crypto.jwt.JwtProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public JwtProvider jwtProvider(JwtProperties properties) {
        return new JwtProvider(properties.secret());
    }
}
