package com.wedding.upload.service;

import com.wedding.upload.drive.GoogleDriveService;
import com.wedding.upload.line.LineApiClient;
import com.wedding.upload.line.LineContent;
import com.wedding.upload.puzzle.PuzzleImageCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoUploadServiceTest {

    private static final String USER = "U-guest-1";
    private static final byte[] PHOTO = {1, 2, 3, 4};
    private static final String ORIGINAL_URL = "https://wedding.example.com/puzzles/prize1.jpg";
    private static final String PREVIEW_URL =
            "https://wedding.example.com/puzzles/preview/prize1.jpg";
    /** 測試的 debounce 是 300ms；等待上限放寬到 3 秒，慢的機器也不會 flaky。 */
    private static final long TIMEOUT_MS = 3_000;

    @Mock
    private LineApiClient lineApiClient;
    @Mock
    private GoogleDriveService driveService;
    @Mock
    private PuzzleImageCatalog puzzleImageCatalog;

    private PhotoUploadService service;

    @BeforeEach
    void setUp() {
        // 正式環境的 debounce 是 3 秒，測試縮到 300ms。
        service = new PhotoUploadService(
                lineApiClient, driveService, puzzleImageCatalog, Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // ---------- debounce：一次連拍只回一則 ----------

    @Test
    void coalescesABurstOfPhotosIntoASingleReply() {
        givenPhotoDownloadSucceeds();

        service.handleImageMessage("m1", "reply-1", USER);
        service.handleImageMessage("m2", "reply-2", USER);
        service.handleImageMessage("m3", "reply-3", USER);

        // 只用最後一個 reply token 回一次，前兩個計時器都被取消
        verify(lineApiClient, timeout(TIMEOUT_MS)).replyText(eq("reply-3"), anyString());
        verify(lineApiClient, times(1)).replyText(anyString(), anyString());
        verify(lineApiClient, never()).replyText(eq("reply-1"), anyString());
        verify(lineApiClient, never()).replyText(eq("reply-2"), anyString());
    }

    @Test
    void archivesEveryPhotoOfTheBurstEvenThoughOnlyOneReplyIsSent() throws Exception {
        givenPhotoDownloadSucceeds();

        service.handleImageMessage("m1", "reply-1", USER);
        service.handleImageMessage("m2", "reply-2", USER);
        service.handleImageMessage("m3", "reply-3", USER);

        ArgumentCaptor<byte[]> data = ArgumentCaptor.forClass(byte[].class);
        verify(driveService, times(3)).upload(anyString(), eq("image/jpeg"), data.capture());
        assertThat(data.getValue()).isEqualTo(PHOTO);
    }

    @Test
    void repliesToEachGuestSeparately() {
        givenPhotoDownloadSucceeds();

        service.handleImageMessage("m1", "reply-a", "U-a");
        service.handleImageMessage("m2", "reply-b", "U-b");

        verify(lineApiClient, timeout(TIMEOUT_MS)).replyText(eq("reply-a"), anyString());
        verify(lineApiClient, timeout(TIMEOUT_MS)).replyText(eq("reply-b"), anyString());
    }

    // ---------- Drive 歸檔 ----------

    @Test
    void namesTheArchivedFileWithTimestampUserAndMessageId() throws Exception {
        givenPhotoDownloadSucceeds();

        service.handleImageMessage("m-42", "reply-1", USER);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(driveService).upload(name.capture(), anyString(), any());
        assertThat(name.getValue()).matches("\\d{8}-\\d{6}_U-guest-1_m-42\\.jpg");
    }

    @Test
    void keepsThePngExtensionForPngUploads() throws Exception {
        when(lineApiClient.getMessageContent("m1"))
                .thenReturn(new LineContent(PHOTO, "image/png"));

        service.handleImageMessage("m1", "reply-1", USER);

        verify(driveService).upload(endsWith(".png"), eq("image/png"), any());
    }

    @Test
    void stillSendsThePuzzleWhenTheDriveUploadFails() throws Exception {
        givenPhotoDownloadSucceeds();
        when(driveService.upload(anyString(), anyString(), any()))
                .thenThrow(new IOException("drive is down"));

        service.handleImageMessage("m1", "reply-1", USER);

        verify(lineApiClient, timeout(TIMEOUT_MS)).replyText(eq("reply-1"), contains("小謎題"));
    }

    @Test
    void archivesPhotosThatArriveWithoutAReplyToken() throws Exception {
        givenPhotoDownloadSucceeds();

        service.handleImageMessage("m1", null, USER);

        verify(driveService).upload(anyString(), anyString(), any());
        verify(lineApiClient, never()).replyText(anyString(), anyString());
    }

    // ---------- 猜謎 ----------

    @Test
    void rewardsTheCorrectAnswerWithABlessingImageAndText() {
        String answer = assignPuzzleTo(USER, "reply-1");
        givenABlessingImageIsAvailable();

        service.handleTextMessage(answer, "reply-2", USER);

        verify(lineApiClient).replyImageAndText(
                eq("reply-2"), eq(ORIGINAL_URL), eq(PREVIEW_URL), anyString());
        // 題目用掉就該清掉
        assertThat(service.assignedAnswerFor(USER)).isNull();
    }

    @Test
    void acceptsAnAnswerTypedWithSpacesBetweenTheCharacters() {
        String answer = assignPuzzleTo(USER, "reply-1");
        givenABlessingImageIsAvailable();

        service.handleTextMessage(String.join(" ", answer.split("")), "reply-2", USER);

        verify(lineApiClient)
                .replyImageAndText(eq("reply-2"), anyString(), anyString(), anyString());
    }

    @Test
    void staysSilentOnAWrongAnswerSoTheGuestCanKeepGuessing() {
        String answer = assignPuzzleTo(USER, "reply-1");

        service.handleTextMessage("隨便亂猜", "reply-2", USER);

        verifyNoReplyWasSent();
        assertThat(service.assignedAnswerFor(USER)).isEqualTo(answer);
    }

    @Test
    void doesNotTreatTheRichMenuKeywordsAsAnswers() {
        String answer = assignPuzzleTo(USER, "reply-1");

        service.handleTextMessage("電子喜帖", "reply-2", USER);
        service.handleTextMessage("婚禮資訊", "reply-3", USER);

        verifyNoReplyWasSent();
        assertThat(service.assignedAnswerFor(USER)).isEqualTo(answer);
    }

    @Test
    void ignoresTextThatIsNotExactlyFourCharacters() {
        String answer = assignPuzzleTo(USER, "reply-1");

        service.handleTextMessage(answer + "啦", "reply-2", USER);
        service.handleTextMessage("恭喜", "reply-3", USER);

        verifyNoReplyWasSent();
        assertThat(service.assignedAnswerFor(USER)).isEqualTo(answer);
    }

    @Test
    void ignoresAnswersFromGuestsWithNoPuzzleAssigned() {
        service.handleTextMessage("永結同心", "reply-1", "U-never-sent-a-photo");

        verifyNoReplyWasSent();
    }

    @Test
    void rewardsTheSameAnswerOnlyOnce() {
        String answer = assignPuzzleTo(USER, "reply-1");
        givenABlessingImageIsAvailable();

        service.handleTextMessage(answer, "reply-2", USER);
        service.handleTextMessage(answer, "reply-3", USER);

        verify(lineApiClient, times(1))
                .replyImageAndText(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackToPlainTextWhenNoBlessingImageIsAvailable() {
        String answer = assignPuzzleTo(USER, "reply-1");
        when(puzzleImageCatalog.pickRandomImage()).thenReturn(Optional.empty());

        service.handleTextMessage(answer, "reply-2", USER);

        verify(lineApiClient, never())
                .replyImageAndText(anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(lineApiClient).replyText(eq("reply-2"), text.capture());
        assertThat(text.getValue()).isNotBlank();
    }

    // ---------- helpers ----------

    private void givenPhotoDownloadSucceeds() {
        when(lineApiClient.getMessageContent(anyString()))
                .thenReturn(new LineContent(PHOTO, "image/jpeg"));
    }

    private void givenABlessingImageIsAvailable() {
        when(puzzleImageCatalog.pickRandomImage()).thenReturn(
                Optional.of(new PuzzleImageCatalog.PuzzleImage(ORIGINAL_URL, PREVIEW_URL)));
    }

    /**
     * 傳一張照片、等 debounce 出題，回傳這位賓客這次被指派到的成語，
     * 並清掉出題造成的 mock 互動紀錄，讓後面的 verify 只看猜謎那一段。
     */
    private String assignPuzzleTo(String userId, String replyToken) {
        givenPhotoDownloadSucceeds();
        service.handleImageMessage("m-assign", replyToken, userId);
        verify(lineApiClient, timeout(TIMEOUT_MS)).replyText(eq(replyToken), anyString());

        String answer = service.assignedAnswerFor(userId);
        assertThat(answer).isNotBlank();
        clearInvocations(lineApiClient, driveService);
        return answer;
    }

    private void verifyNoReplyWasSent() {
        verify(lineApiClient, never()).replyText(anyString(), anyString());
        verify(lineApiClient, never())
                .replyImageAndText(anyString(), anyString(), anyString(), anyString());
    }
}
