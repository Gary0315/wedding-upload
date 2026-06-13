package com.wedding.upload.line;

import com.wedding.upload.config.LineProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class LineSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final LineProperties properties;

    public LineSignatureVerifier(LineProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(byte[] body, String signature) {
        if (signature == null || properties.channelSecret() == null || properties.channelSecret().isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.channelSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(body));
            // Constant-time comparison to avoid timing attacks.
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
