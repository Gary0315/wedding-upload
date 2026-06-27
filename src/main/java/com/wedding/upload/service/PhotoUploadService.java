package com.wedding.upload.service;

import com.wedding.upload.drive.GoogleDriveService;
import com.wedding.upload.line.LineApiClient;
import com.wedding.upload.line.LineContent;
import com.wedding.upload.puzzle.PuzzleImageCatalog;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long REPLY_DEBOUNCE_SECONDS = 3;
    private static final String ANONYMOUS_USER_KEY = "__anonymous__";
    private static final int ANSWER_LENGTH = 4;
    // 這些是 LINE OA 選單按鈕的文字，雖然剛好也是 4 個字，但不算謎題答案。
    private static final Set<String> RESERVED_KEYWORDS = Set.of("電子喜帖", "婚禮資訊");
    // 答對謎題後跟著圖片一起回的文字，從這個 List 裡隨機抽一句。
    // 想新增 / 修改文案就直接編這個清單。
    private static final List<String> CORRECT_ANSWER_TEXTS = List.of(
            "答對啦！謝謝你參與我們的小遊戲，期待在婚禮上見到你喔！",
            "恭喜闖關成功！送上我們的Q版珍藏照，謝謝你一直以來的陪伴與祝福。",
            "聰明如你！謝謝你和我們一起分享這份喜悅，有你的祝福，我們更幸福了。",
            "哎喲，很聰明嘛！既然被你答對了，這張限定版Q版照就讓你私藏啦!",
            "答對了！加碼送上我們滿滿的幸福能量，今天也要開開心心喔！",
            "傳說中智商爆棚的賓客就是你！恭喜獲得神仙眷侶（自稱）照片一張！",
            "答對囉！願這份喜氣也帶給你滿滿的好運，事事順心、幸福美滿！",
            "恭喜過關！祝福你接下來的日子就像這道題一樣，迎刃而解、好運連連！",
            "解謎成功！送上我們最真摯的祝福，願你天天都有好心情，『字』得其樂！",
            "解謎成功！謝謝你走進我們的幸福故事。",
            "恭喜答對！收下這份喜悅，今天也要幸福喔！",
            "答對啦！期待與你一起見證我們的幸福時刻。"
    );

    // 想新增謎題就在這個 List 裡加一行 new Puzzle("emoji 組合", "提示", "答案")。
    // 第三個欄位「答案」會拿來：
    //   1) 比對使用者回的文字
    //   2) 從 static/puzzles/{答案}/ 撈圖片
    private static final List<Puzzle> PUZZLES = List.of(
            new Puzzle("♾️ + 🎀 + 👫 + ❤️", "猜一個成語", "永結同心"),
            new Puzzle("💯 + 📅 + 👍 + 🈴", "猜一個成語", "百年好合"),
            new Puzzle("🌅 + 🥜 + 💰 + 👶", "猜一個成語", "早生貴子"),
            new Puzzle("🌸 + 👍 + 🌕 + ⭕", "猜一個成語", "花好月圓"),
            new Puzzle("🦳 + 👴 + 🤝 + 👵", "猜一個成語", "白頭偕老"),
            new Puzzle("🍯 + 🍯 + 🐝 + 🐝", "猜一個成語", "甜甜蜜蜜"),
            new Puzzle("☁️ + 🛠️ + 🧀 + 🈴", "猜一個成語", "天作之合"),
            new Puzzle("☀️ + 🏀 + 🍝 + 📦", "猜一個成語", "情投意合"),
            new Puzzle("❤️ + ❤️ + 🐘 + 🖨️", "猜一個成語", "心心相印"),
            new Puzzle("🎹 + 🌈 + 🕊️ + 🐦", "猜一個成語", "琴瑟和鳴"),
            new Puzzle("🐺 + 🥬 + 👧 + 🐱", "猜一個成語", "郎才女貌"),
            new Puzzle("🚪 + 🔔 + 🐯 + ✔️", "猜一個成語", "門當戶對"),
            new Puzzle("🎯 + 🀄 + 💉 + 📌", "猜一個成語", "命中注定"),
            new Puzzle("🏋️ + 🌑 + 🐧 + 🍓", "猜一個成語", "舉案齊眉"),
            new Puzzle("🐝 + 🕊️ + ✌️ + ✈️", "猜一個成語", "比翼雙飛")
    );

    private final LineApiClient lineApiClient;
    private final GoogleDriveService driveService;
    private final PuzzleImageCatalog puzzleImageCatalog;
    private final ScheduledExecutorService replyScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "photo-reply-debounce");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, PendingReply> pendingReplies = new ConcurrentHashMap<>();
    // userKey -> 該使用者目前該回答的成語。使用者下次傳新照片會被覆蓋。
    private final ConcurrentHashMap<String, String> userAssignments = new ConcurrentHashMap<>();

    public PhotoUploadService(LineApiClient lineApiClient,
                              GoogleDriveService driveService,
                              PuzzleImageCatalog puzzleImageCatalog) {
        this.lineApiClient = lineApiClient;
        this.driveService = driveService;
        this.puzzleImageCatalog = puzzleImageCatalog;
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

    // 使用者傳文字訊息進來：判斷是不是在猜謎題答案。
    @Async
    public void handleTextMessage(String text, String replyToken, String userId) {
        if (text == null || replyToken == null || replyToken.isBlank()) {
            return;
        }
        String normalized = normalize(text);
        // 只有「正好 4 個字」的訊息才當成猜謎；其他文字（含選單關鍵字、聊天）都跳過。
        if (normalized.codePointCount(0, normalized.length()) != ANSWER_LENGTH) {
            return;
        }
        // 跟我們的 LINE OA 選單按鈕同名的訊息直接放行，不算謎題答案。
        if (RESERVED_KEYWORDS.contains(normalized)) {
            return;
        }
        String key = userId != null ? userId : ANONYMOUS_USER_KEY;
        String expectedAnswer = userAssignments.get(key);
        if (expectedAnswer == null) {
            // 使用者目前沒有題目可以答（還沒傳過照片，或上一題答過了），直接忽略。
            return;
        }
        if (!normalized.equals(normalize(expectedAnswer))) {
            // 答錯：保持沉默，不消耗 reply token，讓使用者繼續猜。
            return;
        }
        // 答對了！清除 assignment 並從祝福圖池子隨機抽一張回傳。
        userAssignments.remove(key);
        Optional<String> imageUrl = puzzleImageCatalog.pickRandomImageUrl();
        if (imageUrl.isEmpty()) {
            // 為什麼會 empty？PuzzleImageCatalog 內部已經印出原因 WARN（沒圖、或 baseUrl 沒設）。
            // 這裡不重複印訊息，免得混淆。
            // 沒圖也別放生使用者，至少回個文字祝福
            try {
                lineApiClient.replyText(replyToken, pickCorrectAnswerText());
            } catch (Exception e) {
                log.error("Failed to send fallback text reply", e);
            }
            return;
        }
        try {
            lineApiClient.replyImageAndText(
                    replyToken,
                    imageUrl.get(),
                    pickCorrectAnswerText());
        } catch (Exception e) {
            log.error("Failed to send blessing image+text for {}", expectedAnswer, e);
        }
    }

    private String pickCorrectAnswerText() {
        return CORRECT_ANSWER_TEXTS.get(ThreadLocalRandom.current().nextInt(CORRECT_ANSWER_TEXTS.size()));
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
        Puzzle puzzle = pickPuzzle();
        // 記下「這個使用者該答的成語」，等他回文字時比對。
        userAssignments.put(key, puzzle.answer());
        try {
            lineApiClient.replyText(pending.replyToken(), buildReplyMessage(puzzle));
        } catch (Exception e) {
            log.error("Failed to send batched reply for {}", key, e);
        }
    }

    private Puzzle pickPuzzle() {
        return PUZZLES.get(ThreadLocalRandom.current().nextInt(PUZZLES.size()));
    }

    private String buildReplyMessage(Puzzle puzzle) {
        return "照片已收到，謝謝你的分享！\n\n這裡有個小謎題\n%s\n(%s)\n\n如果回答成功的話\n會有小驚喜唷！"
                .formatted(puzzle.emojis(), puzzle.hint());
    }

    // 比對前先把全形/半形空白和換行清掉，避免「永 結 同 心」這種輸入被判錯。
    private static String normalize(String s) {
        return s.replaceAll("\\s+", "").trim();
    }

    @PreDestroy
    void shutdown() {
        replyScheduler.shutdownNow();
    }

    private record PendingReply(String replyToken, ScheduledFuture<?> future) {}

    private record Puzzle(String emojis, String hint, String answer) {}

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
