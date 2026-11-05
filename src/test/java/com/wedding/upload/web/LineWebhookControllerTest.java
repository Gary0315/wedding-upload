package com.wedding.upload.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wedding.upload.line.LineSignatureVerifier;
import com.wedding.upload.service.PhotoUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineWebhookControllerTest {

    @Mock
    private LineSignatureVerifier signatureVerifier;
    @Mock
    private PhotoUploadService photoUploadService;

    private LineWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new LineWebhookController(
                signatureVerifier, photoUploadService, new ObjectMapper());
    }

    @Test
    void rejectsRequestWhoseSignatureDoesNotVerify() {
        when(signatureVerifier.isValid(any(), any())).thenReturn(false);

        ResponseEntity<Void> response = callback("""
                {"events":[{"type":"message","replyToken":"r1",
                 "source":{"userId":"U1"},"message":{"type":"image","id":"m1"}}]}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(photoUploadService);
    }

    @Test
    void handsImageMessagesToTheUploadService() {
        givenValidSignature();

        ResponseEntity<Void> response = callback("""
                {"events":[{"type":"message","replyToken":"r1",
                 "source":{"userId":"U1"},"message":{"type":"image","id":"m1"}}]}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(photoUploadService).handleImageMessage("m1", "r1", "U1");
    }

    @Test
    void handsTextMessagesToTheUploadService() {
        givenValidSignature();

        callback("""
                {"events":[{"type":"message","replyToken":"r2",
                 "source":{"userId":"U1"},"message":{"type":"text","text":"永結同心"}}]}""");

        verify(photoUploadService).handleTextMessage("永結同心", "r2", "U1");
    }

    @Test
    void processesEveryEventInABatchedDelivery() {
        givenValidSignature();

        callback("""
                {"events":[
                 {"type":"message","replyToken":"r1","source":{"userId":"U1"},
                  "message":{"type":"image","id":"m1"}},
                 {"type":"message","replyToken":"r2","source":{"userId":"U1"},
                  "message":{"type":"image","id":"m2"}}]}""");

        verify(photoUploadService).handleImageMessage("m1", "r1", "U1");
        verify(photoUploadService).handleImageMessage("m2", "r2", "U1");
    }

    @Test
    void ignoresEventsThatAreNotMessages() {
        givenValidSignature();

        callback("""
                {"events":[{"type":"follow","replyToken":"r1","source":{"userId":"U1"}}]}""");

        verifyNoInteractions(photoUploadService);
    }

    @Test
    void ignoresUnsupportedMessageTypesSuchAsStickers() {
        givenValidSignature();

        callback("""
                {"events":[{"type":"message","replyToken":"r1","source":{"userId":"U1"},
                 "message":{"type":"sticker","id":"m1"}}]}""");

        verifyNoInteractions(photoUploadService);
    }

    @Test
    void stillAnswers200WhenThePayloadCannotBeParsed() {
        // 回 4xx/5xx 會讓 LINE 重送，同一張照片就會被上傳兩次。
        givenValidSignature();

        ResponseEntity<Void> response = callback("this is not json at all");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(photoUploadService);
    }

    private void givenValidSignature() {
        when(signatureVerifier.isValid(any(), any())).thenReturn(true);
    }

    private ResponseEntity<Void> callback(String body) {
        return controller.callback("signature", body.getBytes(StandardCharsets.UTF_8));
    }
}
