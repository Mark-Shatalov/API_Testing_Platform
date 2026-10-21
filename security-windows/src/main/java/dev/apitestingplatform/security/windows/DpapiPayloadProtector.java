package dev.apitestingplatform.security.windows;

import com.sun.jna.platform.win32.Crypt32Util;
import dev.apitestingplatform.application.storage.PayloadProtector;
import dev.apitestingplatform.application.storage.StorageException;

/** Current-Windows-user DPAPI; never falls back to plaintext or machine-wide protection. */
public final class DpapiPayloadProtector implements PayloadProtector {
    @Override public byte[] protect(byte[] plaintext) {
        try { return Crypt32Util.cryptProtectData(plaintext, 1); } // CRYPTPROTECT_UI_FORBIDDEN
        catch (RuntimeException | LinkageError failure) { throw new StorageException("Windows could not protect local request data."); }
    }
    @Override public byte[] unprotect(byte[] bytes) {
        try { return Crypt32Util.cryptUnprotectData(bytes, 1); }
        catch (RuntimeException | LinkageError failure) { throw new StorageException("Protected data could not be opened by this Windows user."); }
    }
}
