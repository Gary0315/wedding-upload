package com.wedding.upload.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wedding.upload.line.LineSignatureVerifier;
import com.wedding.upload.service.PhotoUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);

    private final LineSignatureVerifier signatureVerifier;
    private final PhotoUploadService photoUploadService;
    private final ObjectMapper objectMapper;

    public LineWebhookController(LineSignatureVerifier signatureVerifier,
                                 PhotoUploadService photoUploadService,
                                 ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.photoUploadService = photoUploadService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody byte[] body) {

        if (!signatureVerifier.isValid(body, signature)) {
            log.warn("Rejected webhook request with invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            JsonNode events = objectMapper.readTree(body).path("events");
            for (JsonNode event : events) {
                handleEvent(event);
            }
        } catch (Exception e) {
            log.error("Failed to parse LINE webhook payload", e);
        }

        // Always reply 200 quickly so LINE does not retry the delivery.
        return ResponseEntity.ok().build();
    }

    private void handleEvent(JsonNode event) {
        if (!"message".equals(event.path("type").asText())) {
            return;
        }
        JsonNode message = event.path("message");
        String messageType = message.path("type").asText();
        String replyToken = event.path("replyToken").asText(null);
        String userId = event.path("source").path("userId").asText(null);

        switch (messageType) {
            case "image" -> {
                String messageId = message.path("id").asText();
                photoUploadService.handleImageMessage(messageId, replyToken, userId);
            }
            case "text" -> {
                String text = message.path("text").asText("");
                photoUploadService.handleTextMessage(text, replyToken, userId);
            }
            default -> {
                // 其他訊息類型（貼圖、影片等）目前不處理
            }
        }
    }
}
