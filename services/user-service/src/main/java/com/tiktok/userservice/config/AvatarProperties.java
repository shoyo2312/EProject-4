package com.tiktok.userservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.avatar")
public record AvatarProperties(List<String> allowedHosts) {
}
