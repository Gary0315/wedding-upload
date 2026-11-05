package com.wedding.upload.puzzle;

import com.wedding.upload.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑在真正打包進 classpath 的那批祝福圖上，所以同時也是
 * 「每張原圖都要有同檔名縮圖」這個上線前踩過的坑的回歸測試。
 */
class PuzzleImageCatalogTest {

    private static final String BASE_URL = "https://wedding.example.com";

    @Test
    void scansTheBundledBlessingImages() {
        assertThat(scannedCatalog(BASE_URL).snapshot())
                .isNotEmpty()
                .allSatisfy(name -> assertThat(name).matches("(?i).+[.](jpg|jpeg|png|gif)"));
    }

    @Test
    void doesNotTreatThePreviewDirectoryAsABlessingImage() {
        assertThat(scannedCatalog(BASE_URL).snapshot()).doesNotContain("preview");
    }

    @Test
    void everyPickPairsTheOriginalWithASameNamePreview() {
        PuzzleImageCatalog catalog = scannedCatalog(BASE_URL);

        for (int i = 0; i < 50; i++) {
            PuzzleImageCatalog.PuzzleImage image = catalog.pickRandomImage().orElseThrow();

            assertThat(image.originalUrl()).startsWith(BASE_URL + "/puzzles/");
            String filename = image.originalUrl()
                    .substring(image.originalUrl().lastIndexOf("/") + 1);
            // previewImageUrl 只能到 1MB，一定要指向 preview/ 底下的同檔名縮圖，
            // 否則聊天室裡的預覽會是一片空白。
            assertThat(image.previewUrl()).isEqualTo(BASE_URL + "/puzzles/preview/" + filename);
        }
    }

    @Test
    void drawsFromTheWholePoolRatherThanAlwaysTheSameImage() {
        PuzzleImageCatalog catalog = scannedCatalog(BASE_URL);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            seen.add(catalog.pickRandomImage().orElseThrow().originalUrl());
        }

        assertThat(seen).hasSizeGreaterThan(1);
    }

    @Test
    void returnsEmptyWhenThePublicBaseUrlIsNotConfigured() {
        assertThat(scannedCatalog(null).pickRandomImage()).isEmpty();
        assertThat(scannedCatalog("").pickRandomImage()).isEmpty();
    }

    @Test
    void toleratesATrailingSlashInThePublicBaseUrl() {
        String url = scannedCatalog(BASE_URL + "/").pickRandomImage().orElseThrow().originalUrl();

        assertThat(url).doesNotContain("//puzzles");
    }

    private static PuzzleImageCatalog scannedCatalog(String baseUrl) {
        PuzzleImageCatalog catalog = new PuzzleImageCatalog(new AppProperties(baseUrl));
        catalog.scan();
        return catalog;
    }
}
