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
     * 回傳圖片訊息。originalContentUrl 與 previewImageUrl 都要是
     * 公開可存取的 HTTPS 網址，而且兩者的大小上限不一樣：
     * 原圖最大 10MB，預覽圖最大 1MB。
     * 兩個欄位塞同一張大圖的話，聊天室裡的預覽會載不出來。
     */
    public void replyImage(String replyToken, String originalContentUrl, String previewImageUrl) {
        Map<String, Object> payload = Map.of(
                "replyToken", replyToken,
                "messages", List.of(imageMessage(originalContentUrl, previewImageUrl)));

        apiClient.post()
                .uri("/v2/bot/message/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 同一個 reply token 一次回「一張圖 + 一段文字」。
     * LINE Reply API 一次最多可以塞 5 則訊息，這裡固定送 2 則。
     * 訊息顯示順序：先圖、再文字。
     */
    public void replyImageAndText(String replyToken,
                                  String originalContentUrl,
                                  String previewImageUrl,
                                  String text) {
        Map<String, Object> payload = Map.of(
                "replyToken", replyToken,
                "messages", List.of(
                        imageMessage(originalContentUrl, previewImageUrl),
                        Map.of("type", "text", "text", text)));

        apiClient.post()
                .uri("/v2/bot/message/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private static Map<String, Object> imageMessage(String originalContentUrl, String previewImageUrl) {
        return Map.of(
                "type", "image",
                "originalContentUrl", originalContentUrl,
                "previewImageUrl", previewImageUrl);
    }
}
