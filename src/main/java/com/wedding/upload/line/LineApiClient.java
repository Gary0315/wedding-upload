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

    /**
     * 回傳圖片訊息。LINE 要求 originalContentUrl 與 previewImageUrl 都是
     * 公開可存取的 HTTPS 網址（沒有 redirect、檔案大小限制請見 LINE 文件）。
     * 這裡為了簡單起見，預覽圖直接用原圖網址。
     */
    public void replyImage(String replyToken, String imageUrl) {
        Map<String, Object> payload = Map.of(
                "replyToken", replyToken,
                "messages", List.of(Map.of(
                        "type", "image",
                        "originalContentUrl", imageUrl,
                        "previewImageUrl", imageUrl)));

        apiClient.post()
                .uri("/v2/bot/message/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}
