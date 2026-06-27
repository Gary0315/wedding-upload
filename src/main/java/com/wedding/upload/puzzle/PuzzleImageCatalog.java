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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 啟動時掃描 classpath:/static/puzzles/* 底下的所有圖片，
 * 之後可以用 {@link #pickRandomImageUrl()} 隨機抽一張的公開網址。
 *
 * <p>使用方式：把所有要當「祝福圖」的照片通通丟進
 * {@code src/main/resources/static/puzzles/} 裡，不用分子資料夾。
 * 賓客猜對任何一個成語都會從這個池子隨機抽一張回。
 */
@Component
public class PuzzleImageCatalog {

    private static final Logger log = LoggerFactory.getLogger(PuzzleImageCatalog.class);
    private static final String PUZZLE_FILES_PATTERN = "classpath:/static/puzzles/*";

    private final AppProperties appProperties;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final List<String> imageFilenames = new ArrayList<>();

    public PuzzleImageCatalog(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    void scan() {
        try {
            Resource[] resources = resolver.getResources(PUZZLE_FILES_PATTERN);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || filename.isBlank() || !isImage(filename)) {
                    continue;
                }
                imageFilenames.add(filename);
            }
            log.info("PuzzleImageCatalog 載入完成：{} 張祝福圖", imageFilenames.size());
        } catch (Exception e) {
            log.error("掃描 static/puzzles 時失敗，答對成語時會回退到純文字回覆", e);
        }
    }

    /**
     * 從所有祝福圖裡隨機抽一張，回傳完整的 HTTPS 公開網址。
     * 找不到任何圖片時回 Optional.empty()。
     */
    public Optional<String> pickRandomImageUrl() {
        if (imageFilenames.isEmpty()) {
            return Optional.empty();
        }
        String baseUrl = appProperties.publicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("app.public-base-url 沒設定，無法產生圖片網址");
            return Optional.empty();
        }
        String filename = imageFilenames.get(ThreadLocalRandom.current().nextInt(imageFilenames.size()));
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("puzzles", filename)
                .build()
                .encode()
                .toUriString();
        return Optional.of(url);
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
}
