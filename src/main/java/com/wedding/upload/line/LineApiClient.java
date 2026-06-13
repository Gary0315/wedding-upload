package com.wedding.upload.line;

import com.wedding.upload.config.LineProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class LineApiClient {

    private final RestClient contentClient;
    private final RestClient apiClient;

    public LineApiClient(LineProperties properties) {
        String authorization = "Bearer " + properties.channelToken();
        // Binary content (images, video, audio) is served from the api-data host.
        this.contentClient = RestClient.builder()
                .baseUrl("https://api-data.line.me")
                .defaultHeader("Authorization", authorization)
                .build();
        // Regular Messaging API calls (reply, push, ...) use the api host.
        this.apiClient = RestClient.builder()
                .baseUrl("https://api.line.me")
                .defaultHeader("Authorization", authorization)
                .build();
    }

    public LineContent getMessageContent(String messageId) {
        ResponseEntity<byte[]> response = contentClient.get()
                .uri("/v2/bot/message/{messageId}/content", messageId)
                .retrieve()
                .toEntity(byte[].class);

        MediaType contentType = response.getHeaders().getContentType();
        return new LineContent(
                response.getBody(),
                contentType != null ? contentType.toString() : MediaType.IMAGE_JPEG_VALUE);
    }

    public void replyText(String replyToken, String text) {
        Map<String, Object> payload = Map.of(
                "replyToken", replyToken,
                "messages", List.of(Map.of("type", "text", "text", text)));

        apiClient.post()
                .uri("/v2/bot/message/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
