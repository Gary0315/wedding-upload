package com.wedding.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.drive")
public record DriveProperties(
        String clientId,
        String clientSecret,
        String refreshToken,
        String folderId) {
}
