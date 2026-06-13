package com.wedding.upload.service;

import com.wedding.upload.drive.GoogleDriveService;
import com.wedding.upload.line.LineApiClient;
import com.wedding.upload.line.LineContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final LineApiClient lineApiClient;
    private final GoogleDriveService driveService;

    public PhotoUploadService(LineApiClient lineApiClient, GoogleDriveService driveService) {
        this.lineApiClient = lineApiClient;
        this.driveService = driveService;
    }

    // Runs off the webhook thread so we can return 200 to LINE immediately.
    @Async
    public void handleImageMessage(String messageId, String replyToken, String userId) {
        try {
            LineContent content = lineApiClient.getMessageContent(messageId);
            String filename = buildFilename(userId, messageId, content.contentType());
            String fileId = driveService.upload(filename, content.contentType(), content.data());
            log.info("Uploaded LINE image {} to Drive file {}", messageId, fileId);

            if (replyToken != null && !replyToken.isBlank()) {
                lineApiClient.replyText(replyToken, "照片已收到，謝謝你的分享！");
            }
        } catch (Exception e) {
            log.error("Failed to save LINE image {} to Google Drive", messageId, e);
        }
    }

    private String buildFilename(String userId, String messageId, String contentType) {
        String safeUser = userId != null ? userId : "unknown";
        return "%s_%s_%s%s".formatted(
                LocalDateTime.now().format(TIMESTAMP),
                safeUser,
                messageId,
                extensionFor(contentType));
    }

    private String extensionFor(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        if (contentType.contains("png")) {
            return ".png";
        }
        if (contentType.contains("gif")) {
            return ".gif";
        }
        return ".jpg";
    }
}
