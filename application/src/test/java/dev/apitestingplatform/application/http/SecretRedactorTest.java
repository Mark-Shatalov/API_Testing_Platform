package dev.apitestingplatform.application.http;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SecretRedactorTest {
    @Test void masksStandardCredentialHeadersRegardlessOfCase() {
        var redactor = SecretRedactor.empty();
        for (String name : List.of("Authorization", "COOKIE", "Set-Cookie", "Proxy-Authorization"))
            assertThat(redactor.header(name, "private-marker")).isEqualTo("[secret]");
        assertThat(redactor.header("Content-Type", "application/json")).isEqualTo("application/json");
    }
    @Test void masksRawUrlEncodedAndJsonEscapedSecretsWithoutRescanningReplacementText() {
        var redactor = new SecretRedactor(List.of("private marker", "secret", "a\"b", "Ab/Cd"), List.of("X-Access"), List.of("custom_key"));
        assertThat(redactor.text("private marker private%20marker a\\\"b secret")).isEqualTo("[secret] [secret] [secret] [secret]");
        assertThat(redactor.url("https://user:password@example.com/path?custom_key=private&public=ok#fragment"))
                .isEqualTo("https://[secret]@example.com/path?custom_key=%5Bsecret%5D&public=ok");
        assertThat(redactor.header("X-Access", "anything")).isEqualTo("[secret]");
        assertThat(redactor.text("Ab%2FCd Ab%2fCd")).isEqualTo("[secret] [secret]");
        assertThat(redactor.toString()).doesNotContain("private marker");
    }
}
