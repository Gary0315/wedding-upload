package com.wedding.upload.service;

import com.wedding.upload.drive.GoogleDriveService;
import com.wedding.upload.line.LineApiClient;
import com.wedding.upload.line.LineContent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long REPLY_DEBOUNCE_SECONDS = 3;
    private static final String ANONYMOUS_USER_KEY = "__anonymous__";

    private final LineApiClient lineApiClient;
    private final GoogleDriveService driveService;
    private final ScheduledExecutorService replyScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "photo-reply-debounce");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, PendingReply> pendingReplies = new ConcurrentHashMap<>();

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
                scheduleBatchedReply(userId, replyToken);
            }
        } catch (Exception e) {
            log.error("Failed to save LINE image {} to Google Drive", messageId, e);
        }
    }

    // Coalesces rapid-fire image uploads into a single reply per user.
    // Each new photo cancels the previous timer and resets it, so the
    // reply only fires after REPLY_DEBOUNCE_SECONDS of silence.
    private void scheduleBatchedReply(String userId, String replyToken) {
        String key = userId != null ? userId : ANONYMOUS_USER_KEY;
        pendingReplies.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.future().cancel(false);
            }
            ScheduledFuture<?> future = replyScheduler.schedule(
                    () -> flushReply(k),
                    REPLY_DEBOUNCE_SECONDS,
                    TimeUnit.SECONDS);
            return new PendingReply(replyToken, future);
        });
    }

    private void flushReply(String key) {
        PendingReply pending = pendingReplies.remove(key);
        if (pending == null) {
            return;
        }
        try {
            lineApiClient.replyText(pending.replyToken(), "照片已收到，謝謝你的分享！");
        } catch (Exception e) {
            log.error("Failed to send batched reply for {}", key, e);
        }
    }

    @PreDestroy
    void shutdown() {
        replyScheduler.shutdownNow();
    }

    private record PendingReply(String replyToken, ScheduledFuture<?> future) {}

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
