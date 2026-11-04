package dev.apitestingplatform.app;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppUninstallerTest {
    @Test void findsOnlyTheWindowsInstallerEntryWithTheExactName() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("API Testing Platform", "API Testing Platform");                         // not a product code
        entries.put("{11111111-2222-3333-4444-555555555555}", "API Testing Platform Helper"); // different name
        entries.put("{B8247EAE-9F93-33FF-898F-8E7E89D0FB06}", "API Testing Platform");
        entries.put("{AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}", "Other application");

        assertThat(AppUninstaller.productCode(entries)).contains("{B8247EAE-9F93-33FF-898F-8E7E89D0FB06}");
        assertThat(AppUninstaller.productCode(Map.of("{AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE}", "Other"))).isEmpty();
    }

    @Test void runsTheStandardWindowsInstallerUninstallWithoutArbitraryArguments() {
        assertThat(AppUninstaller.uninstallCommand("{B8247EAE-9F93-33FF-898F-8E7E89D0FB06}"))
                .containsExactly("msiexec.exe", "/x", "{B8247EAE-9F93-33FF-898F-8E7E89D0FB06}", "/passive");
        assertThatThrownBy(() -> AppUninstaller.uninstallCommand("{B8247EAE} /qn & del C:\\"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
