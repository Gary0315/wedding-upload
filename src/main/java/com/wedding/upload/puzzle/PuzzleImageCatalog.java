package com.wedding.upload.puzzle;

import com.wedding.upload.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 啟動時掃描 classpath:/static/puzzles/ 底下的所有圖片，
 * 之後可以用 {@link #pickRandomImage()} 隨機抽一張的公開網址。
 *
 * <p>使用方式：把所有要當「祝福圖」的照片通通丟進
 * {@code src/main/resources/static/puzzles/} 裡，不用分子資料夾。
 * 賓客猜對任何一個成語都會從這個池子隨機抽一張回。
 *
 * <p>{@code static/puzzles/preview/} 是給縮圖用的子資料夾：放一張「同檔名」的小圖，
 * 回訊息時就會拿它當 LINE 的 previewImageUrl。LINE 對預覽圖有 1MB 的限制，
 * 原圖通常會超過，所以沒有縮圖的話聊天室裡很可能是一片空白。
 * 找不到對應縮圖時會退回用原圖，並在啟動時印出 WARN。
 */
@Component
public class PuzzleImageCatalog {

    private static final Logger log = LoggerFactory.getLogger(PuzzleImageCatalog.class);
    private static final String PUZZLE_FILES_PATTERN = "classpath:/static/puzzles/*";
    private static final String PREVIEW_FILES_PATTERN = "classpath:/static/puzzles/preview/*";

    private final AppProperties appProperties;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final List<String> imageFilenames = new ArrayList<>();
    private final Set<String> previewFilenames = new LinkedHashSet<>();

    public PuzzleImageCatalog(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    void scan() {
        imageFilenames.addAll(scanImages(PUZZLE_FILES_PATTERN, "static/puzzles"));
        previewFilenames.addAll(scanImages(PREVIEW_FILES_PATTERN, "static/puzzles/preview"));
        log.info("PuzzleImageCatalog 載入完成：{} 張祝福圖、{} 張縮圖",
                imageFilenames.size(), previewFilenames.size());

        List<String> missingPreviews = imageFilenames.stream()
                .filter(filename -> !previewFilenames.contains(filename))
                .toList();
        if (!missingPreviews.isEmpty()) {
            log.warn("這些祝福圖沒有對應的縮圖，會直接拿原圖當 previewImageUrl；"
                    + "只要原圖超過 1MB，LINE 就可能顯示不出來："
                    + "{}（解法：放一張同檔名的小圖到 static/puzzles/preview/）", missingPreviews);
        }
    }

    private List<String> scanImages(String pattern, String description) {
        List<String> filenames = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources(pattern)) {
                String filename = resource.getFilename();
                // 目錄（例如 puzzles/ 底下的 preview/）沒有圖片副檔名，會在這裡被濾掉。
                if (filename == null || filename.isBlank() || !isImage(filename)) {
                    continue;
                }
                filenames.add(filename);
            }
        } catch (Exception e) {
            log.error("掃描 {} 時失敗", description, e);
        }
        return filenames;
    }

    /**
     * 從所有祝福圖裡隨機抽一張，回傳原圖 + 縮圖的完整 HTTPS 公開網址。
     * 找不到任何圖片、或沒設定 public-base-url 時回 Optional.empty()。
     */
    public Optional<PuzzleImage> pickRandomImage() {
        if (imageFilenames.isEmpty()) {
            return Optional.empty();
        }
        String baseUrl = appProperties.publicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("app.public-base-url 沒設定，無法產生圖片網址");
            return Optional.empty();
        }
        String filename = imageFilenames.get(ThreadLocalRandom.current().nextInt(imageFilenames.size()));
        String originalUrl = buildUrl(baseUrl, "puzzles", filename);
        // 沒有同檔名的縮圖就退回原圖，至少還是送得出去。
        String previewUrl = previewFilenames.contains(filename)
                ? buildUrl(baseUrl, "puzzles", "preview", filename)
                : originalUrl;
        return Optional.of(new PuzzleImage(originalUrl, previewUrl));
    }

    private static String buildUrl(String baseUrl, String... pathSegments) {
        return UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment(pathSegments)
                .build()
                .encode()
                .toUriString();
    }

    private static boolean isImage(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif");
    }

    /** 提供給單元測試用。 */
    List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(imageFilenames));
    }

    /** 一張祝福圖的兩個網址：原圖給 originalContentUrl，縮圖給 previewImageUrl。 */
    public record PuzzleImage(String originalUrl, String previewUrl) {}
}
