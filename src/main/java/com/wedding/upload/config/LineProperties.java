package com.wedding.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "line")
public record LineProperties(String channelSecret, String channelToken) {
}
