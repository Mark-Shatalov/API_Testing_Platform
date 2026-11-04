package dev.apitestingplatform.app;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds this application's Windows Installer registration and starts the standard uninstall
 * ({@code msiexec /x}). Used by the "Uninstall API Testing Platform" Start-menu shortcut; uninstalling
 * from Windows Settings, Apps runs the same Windows Installer action.
 */
final class AppUninstaller {
    static final String ARGUMENT = "--uninstall";
    static final String DISPLAY_NAME = "API Testing Platform";
    private static final String UNINSTALL_KEY = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
    private static final Pattern PRODUCT_CODE = Pattern.compile("\\{[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}}");

    private AppUninstaller() { }

    /** Picks the Windows Installer product code whose Apps & Features name is exactly this application. */
    static Optional<String> productCode(Map<String, String> displayNamesByKey) {
        return displayNamesByKey.entrySet().stream()
                .filter(entry -> PRODUCT_CODE.matcher(entry.getKey()).matches())
                .filter(entry -> DISPLAY_NAME.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** {@code msiexec /x {code} /passive}: progress only, Windows asks for administrator approval. */
    static List<String> uninstallCommand(String productCode) {
        if (!PRODUCT_CODE.matcher(productCode).matches()) throw new IllegalArgumentException("Not a product code");
        return List.of("msiexec.exe", "/x", productCode, "/passive");
    }

    static Optional<String> installedProductCode() {
        for (WinReg.HKEY root : new WinReg.HKEY[]{WinReg.HKEY_LOCAL_MACHINE, WinReg.HKEY_CURRENT_USER}) {
            Map<String, String> names = new LinkedHashMap<>();
            try {
                if (!Advapi32Util.registryKeyExists(root, UNINSTALL_KEY)) continue;
                for (String key : Advapi32Util.registryGetKeys(root, UNINSTALL_KEY)) {
                    String path = UNINSTALL_KEY + "\\" + key;
                    try {
                        if (Advapi32Util.registryValueExists(root, path, "DisplayName"))
                            names.put(key, Advapi32Util.registryGetStringValue(root, path, "DisplayName"));
                    } catch (RuntimeException unreadable) { /* skip entries this user cannot read */ }
                }
            } catch (RuntimeException | LinkageError unavailable) { continue; }
            Optional<String> code = productCode(names);
            if (code.isPresent()) return code;
        }
        return Optional.empty();
    }

    /** Starts the uninstall and returns at once, so this launcher's files are free when msiexec removes them. */
    static void start(String productCode) throws IOException {
        new ProcessBuilder(uninstallCommand(productCode)).inheritIO().start();
    }
}
