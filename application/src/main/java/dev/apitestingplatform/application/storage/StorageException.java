package dev.apitestingplatform.application.storage;

/** Safe user-facing failure, deliberately not exposing SQL, payloads or native error messages. */
public final class StorageException extends RuntimeException {
    public enum Reason { OTHER, BUSY, FULL, ACCESS, CORRUPT, CLOSED, CAPACITY }
    private final Reason reason;
    public StorageException(String message) { this(Reason.OTHER, message); }
    public StorageException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
