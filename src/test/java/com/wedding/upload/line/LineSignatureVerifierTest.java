package com.wedding.upload.line;

import com.wedding.upload.config.LineProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驗章是整個服務唯一的安全邊界：沒過就不該碰到任何業務邏輯，
 * 所以正負案例都要鎖住。
 */
class LineSignatureVerifierTest {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SECRET = "test-channel-secret";
    private static final byte[] BODY =
            "{\"events\":[{\"type\":\"message\"}]}".getBytes(StandardCharsets.UTF_8);

    private final LineSignatureVerifier verifier =
            new LineSignatureVerifier(new LineProperties(SECRET, "test-token"));

    @Test
    void acceptsSignatureComputedWithTheChannelSecret() throws Exception {
        assertThat(verifier.isValid(BODY, sign(SECRET, BODY))).isTrue();
    }

    @Test
    void rejectsSignatureComputedWithAnotherSecret() throws Exception {
        assertThat(verifier.isValid(BODY, sign("someone-elses-secret", BODY))).isFalse();
    }

    @Test
    void rejectsWhenTheBodyWasTamperedWithAfterSigning() throws Exception {
        String signature = sign(SECRET, BODY);
        byte[] tampered = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid(tampered, signature)).isFalse();
    }

    @Test
    void rejectsMissingSignatureHeader() {
        assertThat(verifier.isValid(BODY, null)).isFalse();
    }

    @Test
    void rejectsGarbageSignatureWithoutThrowing() {
        assertThat(verifier.isValid(BODY, "this-is-not-even-base64")).isFalse();
    }

    @Test
    void rejectsEverythingWhenTheChannelSecretIsNotConfigured() throws Exception {
        String validSignature = sign(SECRET, BODY);

        assertThat(new LineSignatureVerifier(new LineProperties(null, "t"))
                .isValid(BODY, validSignature)).isFalse();
        assertThat(new LineSignatureVerifier(new LineProperties("   ", "t"))
                .isValid(BODY, validSignature)).isFalse();
    }

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}
