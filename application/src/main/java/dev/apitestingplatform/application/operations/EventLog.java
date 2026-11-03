package dev.apitestingplatform.application.operations;

import java.util.*;

/** Structured-only events. No arbitrary messages, URLs, headers, exception text or payload values can be supplied. */
@FunctionalInterface
public interface EventLog {
    enum Level { INFO, WARN, ERROR }
    enum Component { STARTUP, HTTP, STORAGE, RUNNER, TRANSFER, BACKUP, SETTINGS, UI }
    enum Code { APP_STARTED, APP_STOPPED, REQUEST_STARTED, REQUEST_FINISHED, REQUEST_FAILED, RUN_STARTED, RUN_FINISHED,
        STORAGE_FAILED, SETTINGS_SAVED, BACKUP_CREATED, RESTORE_STAGED, RESTORE_CANCELLED, RESTORE_APPLIED, RESTORE_REJECTED, DIAGNOSTICS_CREATED, UNEXPECTED_ERROR,
        TRANSFER_STARTED, TRANSFER_FINISHED, TRANSFER_FAILED }
    enum Metric { HTTP_STATUS, DURATION_MS, CAPTURED_BYTES, REQUEST_COUNT }
    void record(Level level, Component component, Code code, UUID correlationId, Map<Metric, Long> metrics);
    default void record(Level level, Component component, Code code, UUID correlationId) { record(level, component, code, correlationId, Map.of()); }
    static EventLog none() { return (level, component, code, id, metrics) -> { }; }
}
