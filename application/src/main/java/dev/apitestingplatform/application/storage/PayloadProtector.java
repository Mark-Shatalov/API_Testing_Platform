package dev.apitestingplatform.application.storage;

/** Protects complete saved request and recovery payloads, including unknown/custom secrets. */
public interface PayloadProtector {
    byte[] protect(byte[] plaintext);
    byte[] unprotect(byte[] protectedBytes);
}
