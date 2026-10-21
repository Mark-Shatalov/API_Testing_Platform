package dev.apitestingplatform.application.storage;

/** Safe user-facing failure, deliberately not exposing SQL, payloads or native error messages. */
public final class StorageException extends RuntimeException {
    public StorageException(String message) { super(message); }
}
