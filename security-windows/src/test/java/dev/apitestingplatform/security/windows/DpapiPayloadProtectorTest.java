package dev.apitestingplatform.security.windows;

import dev.apitestingplatform.application.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class DpapiPayloadProtectorTest {
    @Test void protectsWithCurrentUserAndRoundTripsWithoutPlaintext() {
        var protector = new DpapiPayloadProtector(); byte[] plain = "Authorization: private-test-secret".getBytes(StandardCharsets.UTF_8);
        byte[] protectedBytes = protector.protect(plain);
        assertThat(protectedBytes).isNotEqualTo(plain);
        assertThat(new String(protectedBytes, StandardCharsets.ISO_8859_1)).doesNotContain("private-test-secret");
        assertThat(protector.unprotect(protectedBytes)).isEqualTo(plain);
    }
    @Test void refusesDamagedCiphertextWithoutLeakingNativeErrorData() {
        var protector = new DpapiPayloadProtector();
        assertThatThrownBy(() -> protector.unprotect(new byte[]{1, 2, 3})).isInstanceOf(StorageException.class)
                .hasMessage("Protected data could not be opened by this Windows user.");
    }
}
